resource "aws_ssm_parameter" "secret" {
  for_each = toset(["postgres_password", "operator_token_sha256", "operator_totp_secret"])

  name  = "/${var.name_prefix}/${each.key}"
  type  = "SecureString"
  value = "CHANGE-ME"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "acme_email" {
  name  = "/${var.name_prefix}/acme_email"
  type  = "String"
  value = "CHANGE-ME"

  lifecycle {
    ignore_changes = [value]
  }
}

# #1181: operator WireGuard peers, one per line: `<name> <base64 public key> <10.8.0.N>`. Public keys only; the
# host generates and keeps its own private key. CHANGE-ME (the placeholder) leaves the VPN down and binds /ops to
# loopback, so a fresh deployment is private by default.
resource "aws_ssm_parameter" "wireguard_peers" {
  name  = "/${var.name_prefix}/wireguard_peers"
  type  = "String"
  value = "CHANGE-ME"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "public_host" {
  name  = "/${var.name_prefix}/public_host"
  type  = "String"
  value = var.public_host
}

resource "aws_ssm_parameter" "alerts_topic_arn" {
  name  = "/${var.name_prefix}/alerts_topic_arn"
  type  = "String"
  value = aws_sns_topic.alerts.arn
}
