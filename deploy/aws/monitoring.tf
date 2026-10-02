resource "aws_sns_topic" "alerts" {
  name = "${var.name_prefix}-alerts"
}

resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

resource "aws_sns_topic_policy" "alerts" {
  arn = aws_sns_topic.alerts.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "cloudwatch.amazonaws.com" }
      Action    = "sns:Publish"
      Resource  = aws_sns_topic.alerts.arn
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
        ArnLike      = { "aws:SourceArn" = "${local.arn_prefix}:cloudwatch:${var.region}:${local.account_id}:alarm:*" }
      }
    }]
  })
}

resource "aws_cloudwatch_log_group" "host" {
  name              = "/${var.name_prefix}/host"
  retention_in_days = 14
  # Reserved for host diagnostics. Host logs are not shipped in S2; metrics only.
}

resource "aws_ssm_parameter" "cloudwatch" {
  name = "AmazonCloudWatch-${var.name_prefix}"
  type = "String"
  value = jsonencode({
    agent = { metrics_collection_interval = 300, run_as_user = "root" }
    metrics = {
      namespace              = "CWAgent"
      append_dimensions      = { InstanceId = "$${aws:InstanceId}" }
      aggregation_dimensions = [["InstanceId"]]
      metrics_collected = {
        mem = {
          measurement           = ["mem_used_percent"]
          drop_original_metrics = ["mem_used_percent"]
        }
        disk = {
          measurement           = ["used_percent"]
          resources             = ["/"]
          drop_device           = true
          drop_original_metrics = ["disk_used_percent"]
        }
      }
    }
  })
}

locals {
  alarms = {
    status = {
      namespace = "AWS/EC2"
      metric    = "StatusCheckFailed"
      threshold = 0
      periods   = 1
      statistic = "Maximum"
    }
    cpu = {
      namespace = "AWS/EC2"
      metric    = "CPUUtilization"
      threshold = 90
      periods   = 3
      statistic = "Average"
    }
    memory = {
      namespace = "CWAgent"
      metric    = "mem_used_percent"
      threshold = 90
      periods   = 3
      statistic = "Average"
    }
    disk = {
      namespace = "CWAgent"
      metric    = "disk_used_percent"
      threshold = 80
      periods   = 1
      statistic = "Average"
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "host" {
  for_each = local.alarms

  alarm_name                = "${var.name_prefix}-${each.key}"
  alarm_description         = "${each.value.metric} on the census host (disk collects only path /)"
  namespace                 = each.value.namespace
  metric_name               = each.value.metric
  comparison_operator       = "GreaterThanThreshold"
  threshold                 = each.value.threshold
  evaluation_periods        = each.value.periods
  period                    = 300
  statistic                 = each.value.statistic
  treat_missing_data        = "missing"
  dimensions                = { InstanceId = aws_instance.census.id }
  alarm_actions             = [aws_sns_topic.alerts.arn]
  ok_actions                = [aws_sns_topic.alerts.arn]
  insufficient_data_actions = [aws_sns_topic.alerts.arn]
}

# Memory usage is always above zero; only missing agent metrics breach this alarm.
resource "aws_cloudwatch_metric_alarm" "agent_heartbeat" {
  alarm_name                = "${var.name_prefix}-agent-heartbeat"
  alarm_description         = "CloudWatch agent stopped reporting memory metrics"
  namespace                 = "CWAgent"
  metric_name               = "mem_used_percent"
  comparison_operator       = "LessThanThreshold"
  threshold                 = 0
  evaluation_periods        = 3
  period                    = 300
  statistic                 = "Average"
  treat_missing_data        = "breaching"
  dimensions                = { InstanceId = aws_instance.census.id }
  alarm_actions             = [aws_sns_topic.alerts.arn]
  ok_actions                = [aws_sns_topic.alerts.arn]
  insufficient_data_actions = [aws_sns_topic.alerts.arn]
}
