variable "region" {
  type    = string
  default = "us-east-2"
}

variable "public_host" {
  type    = string
  default = "census.dashbuddy.trotter.cloud"
}

variable "alert_email" {
  description = "Confirm the SNS subscription sent to this address."
  type        = string
}

variable "github_repository" {
  type    = string
  default = "sjtrotter/dashbuddy-census"

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "Use an owner/repository name."
  }
}

variable "github_owner_id" {
  type    = number
  default = 33046718
}

variable "github_repository_id" {
  type    = number
  default = 1400358566
}

variable "instance_type" {
  description = "Must be compatible with the arm64 AMI."
  type        = string
  default     = "t4g.small"
}

variable "root_volume_gib" {
  type    = number
  default = 20
}

variable "data_volume_gib" {
  type    = number
  default = 20
}

variable "compose_ref" {
  description = "Branch or tag of this repository (git clone --branch does not accept a commit SHA)."
  type        = string
  default     = "main"

  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9._/-]*$", var.compose_ref))
    error_message = "Use a branch or tag containing only letters, digits, dots, underscores, slashes and hyphens."
  }
}

variable "image_ref" {
  # production pins @sha256:<digest>
  type    = string
  default = "ghcr.io/sjtrotter/dashbuddy-census:latest"

  validation {
    condition     = can(regex("^ghcr\\.io/[a-z0-9._/-]+(:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}|@sha256:[a-f0-9]{64})$", var.image_ref))
    error_message = "Use a GHCR image with an explicit tag or sha256 digest."
  }
}

variable "monthly_budget_usd" {
  type    = number
  default = 25
}

variable "hard_ceiling_usd" {
  type    = number
  default = 50
}

variable "name_prefix" {
  type    = string
  default = "dashbuddy-census"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,27}$", var.name_prefix))
    error_message = "Use 1-28 lowercase letters, digits or hyphens, starting with a letter."
  }
}
