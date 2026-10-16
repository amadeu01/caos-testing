#!/bin/sh
# Provisions DynamoDB tables before any Service starts, the way CloudFormation or Terraform would in AWS.
# Keep the table list in sync with DynamoDbLocalResource.TABLES in libs/test-support.
set -eu
ENDPOINT=http://store:8000
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1

until aws dynamodb list-tables --endpoint-url "$ENDPOINT" >/dev/null 2>&1; do
  echo "waiting for store"
  sleep 1
done

for table in titles ratings; do
  if aws dynamodb describe-table --endpoint-url "$ENDPOINT" --table-name "$table" >/dev/null 2>&1; then
    echo "table $table already exists"
  else
    aws dynamodb create-table --endpoint-url "$ENDPOINT" --table-name "$table" \
      --attribute-definitions AttributeName=id,AttributeType=S \
      --key-schema AttributeName=id,KeyType=HASH \
      --billing-mode PAY_PER_REQUEST >/dev/null
    echo "created table $table"
  fi
done
