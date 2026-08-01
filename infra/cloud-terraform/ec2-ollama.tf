# Amazon Linux 2023 최신 AMI. owners 빼면 아무나 올린 공개 AMI가 걸린다(보안).
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

# EC2가 맡을 역할(SSM 접속용). SSH 포트 안 열고 세션 매니저로 들어간다.
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}
resource "aws_iam_role" "ec2_ssm" {
  name               = "${var.project}-ec2-ssm"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}
resource "aws_iam_role_policy_attachment" "ec2_ssm" {
  role       = aws_iam_role.ec2_ssm.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore" # 이거만 붙이면 SSM 동작
}
resource "aws_iam_instance_profile" "ec2_ssm" {
  name = "${var.project}-ec2-ssm"
  role = aws_iam_role.ec2_ssm.name # EC2는 역할을 직접 못 달고 프로파일 통해 단다
}

# Ollama SG: 앱(ECS)만 11434 접근. 22(SSH) 안 열음.
resource "aws_security_group" "ollama" {
  name        = "${var.project}-ollama-sg"
  description = "Ollama from app only"
  vpc_id      = aws_vpc.main.id
  ingress {
    from_port       = 11434
    to_port         = 11434
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"] # 이미지·모델 다운로드
  }
  tags = { Name = "${var.project}-ollama-sg" }
}

resource "aws_instance" "ollama" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = "t3.medium" # 모델 로드용 RAM ~4GB
  subnet_id                   = aws_subnet.public[0].id
  associate_public_ip_address = true # 이미지·모델 pull(NAT 없이 IGW). 인바운드는 SG가 app만 허용.
  vpc_security_group_ids      = [aws_security_group.ollama.id]
  iam_instance_profile        = aws_iam_instance_profile.ec2_ssm.name
  user_data                   = <<-EOF
    #!/bin/bash
    dnf install -y docker
    systemctl enable --now docker
    docker run -d --name ollama --restart unless-stopped -p 11434:11434 -v /opt/ollama:/root/.ollama ollama/ollama:latest
    sleep 10
    docker exec ollama ollama pull ${var.ollama_model}
  EOF
  user_data_replace_on_change = true # user_data 바뀌면 재생성(안 그러면 스크립트 재실행 안 됨: 중지→시작뿐)
  tags                        = { Name = "${var.project}-ollama" }
}
