resource "aws_s3_bucket" "backup" {
  bucket_prefix = "${var.name_prefix}-backup-"
}

resource "aws_s3_bucket_versioning" "backup" {
  bucket = aws_s3_bucket.backup.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backup" {
  bucket = aws_s3_bucket.backup.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "backup" {
  bucket                  = aws_s3_bucket.backup.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "backup" {
  bucket = aws_s3_bucket.backup.id
  # Policy-only filter-floor/ journals are durable; never expire them with observations.
  dynamic "rule" {
    for_each = toset(["census-", "withdrawals/"])
    content {
      id     = "expire-${replace(rule.value, "/", "")}-after-14-days"
      status = "Enabled"
      filter { prefix = rule.value }
      expiration {
        days = 14
      }
      noncurrent_version_expiration {
        noncurrent_days = 14
      }
      abort_incomplete_multipart_upload {
        days_after_initiation = 1
      }
    }
  }
  depends_on = [aws_s3_bucket_versioning.backup]
}

resource "aws_s3_bucket_policy" "backup" {
  bucket = aws_s3_bucket.backup.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "OnlyAccountRootCanDeleteOrChangeRetention"
      Effect    = "Deny"
      Principal = "*"
      Action    = ["s3:DeleteObject", "s3:DeleteObjectVersion", "s3:PutLifecycleConfiguration"]
      Resource  = [aws_s3_bucket.backup.arn, "${aws_s3_bucket.backup.arn}/*"]
      Condition = { ArnNotEquals = { "aws:PrincipalArn" = local.root_arn } }
    }]
  })
  # Install lifecycle BEFORE the policy denies further lifecycle changes to IAM
  # administrators as well as the host. See the runbook for retention changes.
  depends_on = [aws_s3_bucket_lifecycle_configuration.backup]
}
