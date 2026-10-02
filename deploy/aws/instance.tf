data "cloudinit_config" "census" {
  gzip          = true
  base64_encode = true

  part {
    content_type = "text/cloud-config"
    content = templatefile("${path.module}/cloud-init.yaml.tftpl", {
      region            = var.region
      name_prefix       = var.name_prefix
      compose_ref       = var.compose_ref
      github_repository = var.github_repository
      image_ref         = var.image_ref
      backup_bucket     = aws_s3_bucket.backup.id
      agent_parameter   = aws_ssm_parameter.cloudwatch.name
      data_volume_id    = aws_ebs_volume.data.id
    })
  }
}

resource "aws_instance" "census" {
  ami                         = nonsensitive(data.aws_ssm_parameter.ubuntu.value)
  instance_type               = var.instance_type
  subnet_id                   = data.aws_subnet.default.id
  vpc_security_group_ids      = [aws_security_group.census.id]
  iam_instance_profile        = aws_iam_instance_profile.census.name
  associate_public_ip_address = true
  monitoring                  = false
  user_data_base64            = data.cloudinit_config.census.rendered
  user_data_replace_on_change = false

  lifecycle {
    # The template is supplied as base64, so ignore that attribute as well.
    ignore_changes = [ami, user_data, user_data_base64]
  }

  metadata_options {
    http_tokens                 = "required"
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_gib
    encrypted   = true
    # The root holds nothing durable; PostgreSQL and backups use the data volume.
    delete_on_termination = true
  }

  credit_specification {
    cpu_credits = "standard"
  }

  depends_on = [
    aws_iam_role_policy.instance,
    aws_ssm_parameter.secret,
    aws_ssm_parameter.acme_email,
    aws_ssm_parameter.public_host,
    aws_vpc_security_group_egress_rule.ipv4,
    aws_vpc_security_group_egress_rule.ipv6,
  ]
}

resource "aws_ebs_volume" "data" {
  availability_zone = data.aws_subnet.default.availability_zone
  type              = "gp3"
  size              = var.data_volume_gib
  encrypted         = true
  tags              = { Name = "${var.name_prefix}-data" }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_volume_attachment" "data" {
  device_name                    = "/dev/sdf"
  volume_id                      = aws_ebs_volume.data.id
  instance_id                    = aws_instance.census.id
  stop_instance_before_detaching = true
}

resource "aws_eip" "census" {
  domain = "vpc"
}

resource "aws_eip_association" "census" {
  instance_id   = aws_instance.census.id
  allocation_id = aws_eip.census.id
}
