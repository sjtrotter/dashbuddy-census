output "state_bucket" {
  description = "Pass this name to the main module's S3 backend at terraform init."
  value       = aws_s3_bucket.state.id
}
