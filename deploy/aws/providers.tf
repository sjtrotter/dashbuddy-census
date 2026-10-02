provider "aws" {
  region = var.region

  default_tags {
    tags = {
      project    = "dashbuddy-census"
      managed_by = "terraform"
    }
  }
}
