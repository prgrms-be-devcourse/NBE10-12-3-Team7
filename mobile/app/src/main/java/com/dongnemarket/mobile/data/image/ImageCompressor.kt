package com.dongnemarket.mobile.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 사진 선택기가 준 `content://` URI 를 **서버에 올릴 수 있는 크기의 JPEG 바이트**로 굽는다.
 *
 * ### 왜 원본을 그냥 올리지 않나
 * 서버 설정이 `max-file-size: 5MB`, **`max-request-size` 도 5MB** 다.
 * 요즘 폰 사진은 장당 3~5MB 라 원본을 그대로 보내면
 * - 한 장도 한도에 아슬아슬하고,
 * - 통신량·업로드 시간이 그대로 사용자 대기 시간이 된다.
 *
 * 긴 변 [MAX_EDGE]px · JPEG [QUALITY_STEPS] 로 구우면 보통 300~800KB 로 떨어진다.
 * 상세 화면이 그리는 크기를 생각하면 화질 손해는 눈에 띄지 않는다.
 *
 * ### 왜 [ExifInterface] 가 필요한가
 * 카메라는 센서를 회전시키지 않는다 — 가로로 누운 픽셀을 저장하고
 * "보여줄 때 90도 돌려라"는 메모(EXIF Orientation)만 남긴다.
 * 우리가 디코드해서 **다시 인코딩하면 그 메모가 사라진다** → 서버·다른 기기에서 사진이 옆으로 눕는다.
 * 그래서 메모를 읽어 **픽셀 자체를 회전**시켜 구운 뒤 올린다.
 *
 * ### 이 클래스가 Data 계층에 있는 이유
 * `ContentResolver`·`Bitmap` 은 Android 프레임워크다. Domain 이 이걸 알면
 * JVM 단위 테스트에서 도메인 코드를 못 돌린다. 그래서 프레임워크 접촉을 여기 가둔다.
 */
@Singleton
class ImageCompressor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * @param uriString `Uri.toString()` 한 값. UI 가 문자열로 넘겨 준다.
     * @return 업로드할 JPEG 바이트. 실패 사유는 [IOException] 으로 감싸 돌려준다.
     *
     * `Dispatchers.IO` 를 생성자로 주입받지 않고 여기서 직접 쓰는 이유:
     * Dagger 는 Kotlin 의 **기본 인자를 보지 못한다**(`CoroutineDispatcher` 바인딩이 없다며 빌드가 깨진다).
     * 주입하려면 한정자(`@IoDispatcher`)와 모듈을 새로 만들어야 하는데,
     * 이 클래스는 `Bitmap`·`ContentResolver` 때문에 어차피 JVM 단위 테스트로 검증할 수 없어서
     * 디스패처를 갈아 끼울 이유 자체가 없다. 검증은 계기 테스트나 실기에서 한다.
     */
    suspend fun compressToJpeg(uriString: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val decoded = decodeScaled(uri) ?: throw IOException("사진을 읽을 수 없습니다: $uriString")
            val upright = applyExifRotation(uri, decoded)
            encodeUnderLimit(upright).also { upright.recycle() }
        }
    }

    /**
     * 원본을 **메모리에 통째로 올리지 않고** 축소해서 디코딩한다.
     *
     * 2단계인 이유:
     *  1. `inJustDecodeBounds = true` — 픽셀은 읽지 않고 **크기만** 본다(메모리 0).
     *  2. 그 크기로 [inSampleSizeFor] 를 정해 실제 디코딩.
     *
     * 이 과정을 건너뛰고 12MP 사진을 그냥 디코딩하면 `4000×3000×4바이트 ≈ 48MB` 짜리 비트맵이 뜬다.
     * 5장이면 그대로 `OutOfMemoryError` 다. `inSampleSize` 는 디코더가 **읽으면서** 솎아 내므로
     * 큰 비트맵이 애초에 만들어지지 않는다.
     */
    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = inSampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val sampled = openStream(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        // inSampleSize 는 2의 거듭제곱 단위라 목표를 정확히 맞추지 못한다(예: 1440 목표에 1600 이 나옴).
        // 남은 차이만 정확한 배율로 줄인다.
        val longestEdge = maxOf(sampled.width, sampled.height)
        if (longestEdge <= MAX_EDGE) return sampled

        val ratio = MAX_EDGE.toFloat() / longestEdge
        val scaled = Bitmap.createScaledBitmap(
            sampled,
            (sampled.width * ratio).roundToInt().coerceAtLeast(1),
            (sampled.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
        // createScaledBitmap 은 새 비트맵을 만든다. 같은 객체를 돌려줬다면 recycle 하면 안 된다.
        if (scaled !== sampled) sampled.recycle()
        return scaled
    }

    /**
     * 긴 변이 [MAX_EDGE] **이상**이 되는 가장 큰 2의 거듭제곱을 고른다.
     *
     * 목표보다 작아지게 잡으면 이미 잃은 화소를 [decodeScaled] 의 확대로 되살릴 수 없다.
     * 그래서 "조금 크게 디코딩한 뒤 정확히 줄이는" 순서를 지킨다.
     */
    private fun inSampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_EDGE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * EXIF Orientation 을 읽어 픽셀을 실제로 돌린다.
     *
     * 회전 정보가 없거나(스크린샷·다운로드 이미지) 읽지 못하면 원본을 그대로 쓴다 —
     * 여기서 실패했다고 등록 전체를 막을 이유는 없다.
     */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            openStream(uri).use { ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            ) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            // 좌우 반전(셀피 카메라)까지 챙긴다. 회전만 처리하면 거울상 사진이 그대로 올라간다.
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }

        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * 화질을 낮춰 가며 [MAX_UPLOAD_BYTES] 아래로 떨어질 때까지 굽는다.
     *
     * 한 번만 구워도 대개 충분하지만, 노이즈가 많은 사진은 같은 화질에서도 파일이 훨씬 커진다.
     * 마지막 단계에서도 한도를 못 맞추면 그 결과를 그대로 올린다 — 서버가 거부하면
     * 사용자에게 "사진 용량이 너무 큽니다"로 안내되므로 여기서 조용히 실패시키지 않는다.
     */
    private fun encodeUnderLimit(bitmap: Bitmap): ByteArray {
        var last = ByteArray(0)
        for (quality in QUALITY_STEPS) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            last = stream.toByteArray()
            if (last.size <= MAX_UPLOAD_BYTES) return last
        }
        return last
    }

    /** `openInputStream` 은 URI 접근 권한이 없거나 파일이 사라졌으면 null 을 준다. */
    private fun openStream(uri: Uri) =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("사진에 접근할 수 없습니다.")

    private companion object {
        /** 긴 변 최대 픽셀. 상세 화면 최대 표시 폭의 2~3배라 화면에서 열화가 보이지 않는다. */
        const val MAX_EDGE = 1440

        /** 서버 한도 5MB 보다 넉넉히 낮게 잡는다(멀티파트 헤더·바운더리 몫). */
        const val MAX_UPLOAD_BYTES = 4 * 1024 * 1024

        /** 위에서부터 시도한다. 55 아래로는 JPEG 특유의 뭉개짐이 눈에 띈다. */
        val QUALITY_STEPS = intArrayOf(85, 70, 55)
    }
}
