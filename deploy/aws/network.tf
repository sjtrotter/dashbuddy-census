data "aws_vpc" "default" {
  default = true
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_subnet" "default" {
  vpc_id            = data.aws_vpc.default.id
  availability_zone = sort(data.aws_availability_zones.available.names)[0]
  default_for_az    = true
}

resource "aws_security_group" "census" {
  name_prefix = "${var.name_prefix}-"
  description = "HTTPS and ACME only; shell access uses SSM, never SSH"
  vpc_id      = data.aws_vpc.default.id
}

locals {
  ingress_ports = toset(["80", "443"])
  egress_ports = {
    http  = { protocol = "tcp", port = 80 }
    https = { protocol = "tcp", port = 443 }
    dns   = { protocol = "udp", port = 53 }
    dns_t = { protocol = "tcp", port = 53 }
    ntp   = { protocol = "udp", port = 123 }
  }
}

resource "aws_vpc_security_group_ingress_rule" "ipv4" {
  for_each = local.ingress_ports

  security_group_id = aws_security_group.census.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
}

resource "aws_vpc_security_group_ingress_rule" "ipv6" {
  for_each = local.ingress_ports

  security_group_id = aws_security_group.census.id
  cidr_ipv6         = "::/0"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
}

resource "aws_vpc_security_group_egress_rule" "ipv4" {
  for_each = local.egress_ports

  security_group_id = aws_security_group.census.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = each.value.protocol
  from_port         = each.value.port
  to_port           = each.value.port
}

resource "aws_vpc_security_group_egress_rule" "ipv6" {
  for_each = local.egress_ports

  security_group_id = aws_security_group.census.id
  cidr_ipv6         = "::/0"
  ip_protocol       = each.value.protocol
  from_port         = each.value.port
  to_port           = each.value.port
}
