# The instance's ignore_changes for ami keeps its launch AMI despite "current"
# advancing. unattended-upgrades patches the host; adopt a new AMI deliberately
# with terraform apply -replace=aws_instance.census (see README).
data "aws_ssm_parameter" "ubuntu" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id"
}
