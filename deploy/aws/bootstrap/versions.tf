terraform {
  required_version = ">= 1.10"

  # Bootstrap deliberately uses local state. Keep it in a secure backup.
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.67"
    }
  }
}
