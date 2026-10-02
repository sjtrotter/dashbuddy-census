resource "aws_budgets_budget" "monthly" {
  name         = "${var.name_prefix}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 50
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}

resource "aws_budgets_budget" "hard_ceiling" {
  name         = "${var.name_prefix}-hard-ceiling"
  budget_type  = "COST"
  limit_amount = tostring(var.hard_ceiling_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"
}

resource "aws_iam_role" "budget_action" {
  name = "${var.name_prefix}-budget-stop"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "budgets.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
        ArnLike      = { "aws:SourceArn" = "${local.arn_prefix}:budgets::${local.account_id}:*" }
      }
    }]
  })
}

resource "aws_iam_role_policy" "budget_action" {
  name = "stop-census-via-automation"
  role = aws_iam_role.budget_action.id
  # Mirrors AWSBudgetsActions_RolePolicyForResourceAdministrationWithSSM,
  # scoped to this instance and the AWS-StopEC2Instance document only.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Action    = "ec2:StopInstances"
        Resource  = aws_instance.census.arn
        Condition = { "ForAnyValue:StringEquals" = { "aws:CalledVia" = ["ssm.amazonaws.com"] } }
      },
      {
        Effect    = "Allow"
        Action    = "ec2:DescribeInstanceStatus"
        Resource  = "*"
        Condition = { "ForAnyValue:StringEquals" = { "aws:CalledVia" = ["ssm.amazonaws.com"] } }
      },
      {
        Effect = "Allow"
        Action = "ssm:StartAutomationExecution"
        Resource = [
          "${local.arn_prefix}:ssm:${var.region}::document/AWS-StopEC2Instance",
          "${local.arn_prefix}:ssm:${var.region}::automation-definition/AWS-StopEC2Instance:*",
          "${local.arn_prefix}:ssm:${var.region}:${local.account_id}:automation-execution/*",
        ]
      }
    ]
  })
}

resource "aws_budgets_budget_action" "stop" {
  budget_name        = aws_budgets_budget.hard_ceiling.name
  action_type        = "RUN_SSM_DOCUMENTS"
  approval_model     = "AUTOMATIC"
  notification_type  = "ACTUAL"
  execution_role_arn = aws_iam_role.budget_action.arn

  action_threshold {
    action_threshold_type  = "ABSOLUTE_VALUE"
    action_threshold_value = var.hard_ceiling_usd
  }

  definition {
    ssm_action_definition {
      action_sub_type = "STOP_EC2_INSTANCES"
      region          = var.region
      instance_ids    = [aws_instance.census.id]
    }
  }

  subscriber {
    address           = var.alert_email
    subscription_type = "EMAIL"
  }

  depends_on = [aws_iam_role_policy.budget_action]
}

resource "aws_ce_anomaly_monitor" "services" {
  name              = "${var.name_prefix}-services"
  monitor_type      = "DIMENSIONAL"
  monitor_dimension = "SERVICE"
}

resource "aws_ce_anomaly_subscription" "daily" {
  name             = "${var.name_prefix}-daily"
  frequency        = "DAILY"
  monitor_arn_list = [aws_ce_anomaly_monitor.services.arn]

  threshold_expression {
    dimension {
      key           = "ANOMALY_TOTAL_IMPACT_ABSOLUTE"
      match_options = ["GREATER_THAN_OR_EQUAL"]
      values        = ["5"]
    }
  }

  subscriber {
    type    = "EMAIL"
    address = var.alert_email
  }
}
