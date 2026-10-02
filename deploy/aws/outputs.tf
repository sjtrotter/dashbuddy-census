output "instance_id" {
  value = aws_instance.census.id
}

output "region" {
  value = var.region
}

output "github_variables" {
  value = {
    AWS_DEPLOY_ROLE_ARN = aws_iam_role.github_deploy.arn
    CENSUS_INSTANCE_ID  = aws_instance.census.id
    AWS_REGION          = var.region
  }
}

output "elastic_ip" {
  value = aws_eip.census.public_ip
}

output "dns_record_hint" {
  value = "A ${var.public_host} → ${aws_eip.census.public_ip}"
}

output "backup_bucket" {
  value = aws_s3_bucket.backup.id
}

output "state_bucket_hint" {
  value = "Use terraform -chdir=bootstrap output -raw state_bucket; backend values are supplied at init."
}

output "github_deploy_role_arn" {
  value = aws_iam_role.github_deploy.arn
}

output "sns_topic_arn" {
  value = aws_sns_topic.alerts.arn
}

output "ssm_parameter_names" {
  value = merge(
    { for name, parameter in aws_ssm_parameter.secret : name => parameter.name },
    {
      acme_email  = aws_ssm_parameter.acme_email.name
      public_host = aws_ssm_parameter.public_host.name
      cloudwatch  = aws_ssm_parameter.cloudwatch.name
    }
  )
}
