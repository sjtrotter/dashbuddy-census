data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  arn_prefix = "arn:${local.partition}"
  root_arn   = "${local.arn_prefix}:iam::${local.account_id}:root"
}
