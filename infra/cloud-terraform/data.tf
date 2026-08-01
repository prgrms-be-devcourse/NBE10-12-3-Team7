# RDS는 반드시 "서브넷 그룹"(≥2 AZ)에 들어간다. 프라이빗 2a·2b를 묶는다.
resource "aws_db_subnet_group" "main" {
  name       = "${var.project}-db-subnet"
  subnet_ids = aws_subnet.private[*].id # 빼면 RDS가 어디 들어갈지 몰라 생성 불가
  tags       = { Name = "${var.project}-db-subnet" }
}

resource "aws_db_instance" "mysql" {
  identifier                  = "${var.project}-mysql" # AWS 자원명 = marketon-mysql
  engine                      = "mysql"
  engine_version              = "8.0" # 메이저만 고정 → AWS가 최신 마이너 선택
  instance_class              = "db.t3.micro"
  allocated_storage           = 20
  db_name                     = var.db_name     # marketon (앱 접속 DB명)
  username                    = var.db_username # marketon
  manage_master_user_password = true            # 비번을 RDS가 생성→Secrets Manager 보관. 코드에 password를 안 쓴다(핵심).
  multi_az                    = false           # 규모상 단일 AZ. 운영 승격 시 true 한 줄(비용 2배).
  db_subnet_group_name        = aws_db_subnet_group.main.name
  vpc_security_group_ids      = [aws_security_group.rds.id]
  skip_final_snapshot         = true # 포폴/학습용: 삭제 시 최종 스냅샷 생략(운영은 false)
  tags                        = { Name = "${var.project}-mysql" }
}

resource "aws_elasticache_subnet_group" "main" {
  name       = "${var.project}-redis-subnet"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_elasticache_cluster" "redis" {
  cluster_id         = "${var.project}-redis"
  engine             = "redis"
  engine_version     = "7.1"
  node_type          = "cache.t3.micro"
  num_cache_nodes    = 1 # 단일 노드. 복제/이중화는 replication_group으로 승격(규모 커질 때).
  port               = 6379
  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.redis.id]
  tags               = { Name = "${var.project}-redis" }
}
