# ── VPC: 이 인프라의 사설 네트워크 경계. 모든 자원이 이 안에 산다. ──
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16" # 사설 IP 공간(6만여 개). 온프레미스 VPC와 동일 대역.
  enable_dns_support   = true          # VPC 내부 DNS(10.0.0.2) 사용. 빼면 이름으로 서로 못 찾는다.
  enable_dns_hostnames = true          # 자원에 DNS 이름 부여. RDS 엔드포인트 등에 필요.
  tags                 = { Name = "${var.project}-vpc" }
}

# 가용영역 이름을 리전에서 동적으로 가져온다(하드코딩 회피 → 다른 리전에서도 안 깨짐).
data "aws_availability_zones" "available" {
  state = "available"
}

# ── 퍼블릭 서브넷 ×2 (2a·2b): ALB가 여기 산다. ──
resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.${count.index + 1}.0/24" # 10.0.1.0/24, 10.0.2.0/24
  availability_zone       = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = true # 이 서브넷 인스턴스에 공인 IP 자동 부여(‘퍼블릭’의 조건 하나)
  tags                    = { Name = "${var.project}-public-${count.index + 1}" }
}

# ── 프라이빗 서브넷 ×2 (2a·2b): ECS·RDS·ElastiCache가 여기 산다. ──
resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.${count.index + 11}.0/24" # 10.0.11.0/24, 10.0.12.0/24
  availability_zone = data.aws_availability_zones.available.names[count.index]
  tags              = { Name = "${var.project}-private-${count.index + 1}" }
}

# ── 인터넷 게이트웨이: VPC ↔ 인터넷 관문. 별도 자원이라 만들어서 VPC에 붙인다. ──
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.project}-igw" }
}

# ── 퍼블릭 라우팅 테이블: 0.0.0.0/0 → IGW. 이게 있어야 비로소 ‘퍼블릭’이 된다. ──
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
  tags = { Name = "${var.project}-public-rt" }
}

# 퍼블릭 서브넷을 위 라우팅 테이블에 연결. 빼면 서브넷은 로컬 전용 라우팅만 남아 인터넷 불가.
resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}
