#!/usr/bin/env bash
# Deploys (or updates) the CookAlong fridge-scan backend with the AWS CLI.
#
#   bash backend/deploy.sh            # uses your default AWS profile
#   AWS_PROFILE=other bash backend/deploy.sh
#
# Creates, in REGION (default ap-south-1):
#   - DynamoDB table  cookalong-scans   (on-demand, TTL on "expires": records vanish after an hour)
#   - DynamoDB table  cookalong-recipes (on-demand, the online recipe catalog served by GET /recipes)
#   - DynamoDB table  cookalong-tv      (on-demand, TTL: Alexa <-> TV pairing codes, command queues, TV state)
#   - IAM role        cookalong-fridge-scan-role   (CloudWatch logs + the tables + bedrock:InvokeModel on Amazon Nova)
#   - Lambda          cookalong-fridge-scan        (Python 3.12, reserved concurrency 3 to cap spend)
#   - Function URL    public, CORS limited to the phone page origin
# Prints the Function URL at the end: put it in app/src/main/assets/config.json -> "scanApiUrl".
set -euo pipefail

REGION="${REGION:-ap-south-1}"
NAME="cookalong-fridge-scan"
TABLE="cookalong-scans"
RECIPES_TABLE="cookalong-recipes"
TV_TABLE="cookalong-tv"
ROLE="${NAME}-role"
PHONE_ORIGIN="${PHONE_ORIGIN:-https://cookalong-tv.github.io}"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
echo "Account $ACCOUNT, region $REGION"

# ---- 1. package: our two files only (boto3 ships with the Lambda runtime; Bedrock is called through it)
rm -rf "$BUILD" && mkdir -p "$BUILD"
(cd "$HERE" && python -c "
import zipfile
with zipfile.ZipFile('build/function.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for f in ('lambda_function.py', 'tv_bridge.py'): z.write(f)
")
echo "Package: $(du -h "$BUILD/function.zip" | cut -f1)"
# the AWS CLI on Windows needs a native path (Git Bash paths like /c/... don't work)
ZIP="$BUILD/function.zip"; command -v cygpath >/dev/null && ZIP="$(cygpath -m "$ZIP")"

# ---- 2. DynamoDB table with TTL
if ! aws dynamodb describe-table --region "$REGION" --table-name "$TABLE" >/dev/null 2>&1; then
  aws dynamodb create-table --region "$REGION" --table-name "$TABLE" \
    --attribute-definitions AttributeName=code,AttributeType=S --key-schema AttributeName=code,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST --tags Key=app,Value=cookalong >/dev/null
  aws dynamodb wait table-exists --region "$REGION" --table-name "$TABLE"
  aws dynamodb update-time-to-live --region "$REGION" --table-name "$TABLE" \
    --time-to-live-specification Enabled=true,AttributeName=expires >/dev/null
  echo "Created table $TABLE"
fi
TABLE_ARN="arn:aws:dynamodb:$REGION:$ACCOUNT:table/$TABLE"

# recipe catalog: one item per recipe (see lambda_function.py for the item layout)
if ! aws dynamodb describe-table --region "$REGION" --table-name "$RECIPES_TABLE" >/dev/null 2>&1; then
  aws dynamodb create-table --region "$REGION" --table-name "$RECIPES_TABLE"     --attribute-definitions AttributeName=id,AttributeType=S --key-schema AttributeName=id,KeyType=HASH     --billing-mode PAY_PER_REQUEST --tags Key=app,Value=cookalong >/dev/null
  aws dynamodb wait table-exists --region "$REGION" --table-name "$RECIPES_TABLE"
  echo "Created table $RECIPES_TABLE"
fi
RECIPES_ARN="arn:aws:dynamodb:$REGION:$ACCOUNT:table/$RECIPES_TABLE"

# Alexa bridge (tv_bridge.py): pairing codes and per-TV command queues, shared with the cookalong-alexa skill Lambda
if ! aws dynamodb describe-table --region "$REGION" --table-name "$TV_TABLE" >/dev/null 2>&1; then
  aws dynamodb create-table --region "$REGION" --table-name "$TV_TABLE"     --attribute-definitions AttributeName=pk,AttributeType=S --key-schema AttributeName=pk,KeyType=HASH     --billing-mode PAY_PER_REQUEST --tags Key=app,Value=cookalong >/dev/null
  aws dynamodb wait table-exists --region "$REGION" --table-name "$TV_TABLE"
  aws dynamodb update-time-to-live --region "$REGION" --table-name "$TV_TABLE"     --time-to-live-specification Enabled=true,AttributeName=expires >/dev/null
  echo "Created table $TV_TABLE"
fi
TV_ARN="arn:aws:dynamodb:$REGION:$ACCOUNT:table/$TV_TABLE"

# ---- 3. execution role (least privilege)
TRUST='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
POLICY=$(cat <<EOF
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["logs:CreateLogGroup","logs:CreateLogStream","logs:PutLogEvents"],
  "Resource":"arn:aws:logs:$REGION:$ACCOUNT:log-group:/aws/lambda/$NAME*"},
 {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem","dynamodb:UpdateItem"],"Resource":"$TABLE_ARN"},
 {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:Scan"],"Resource":"$RECIPES_ARN"},
 {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem","dynamodb:UpdateItem","dynamodb:DeleteItem"],"Resource":"$TV_ARN"},
 {"Effect":"Allow","Action":["bedrock:InvokeModel"],"Resource":[
  "arn:aws:bedrock:$REGION:$ACCOUNT:inference-profile/*amazon.nova-*",
  "arn:aws:bedrock:*::foundation-model/amazon.nova-*"]}
]}
EOF
)
if ! aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
  aws iam create-role --role-name "$ROLE" --assume-role-policy-document "$TRUST" --tags Key=app,Value=cookalong >/dev/null
  echo "Created role $ROLE"
  NEW_ROLE=1
fi
aws iam put-role-policy --role-name "$ROLE" --policy-name "${NAME}-access" --policy-document "$POLICY"
ROLE_ARN="arn:aws:iam::$ACCOUNT:role/$ROLE"
[ "${NEW_ROLE:-0}" = 1 ] && { echo "Waiting for the new role to propagate..."; sleep 12; }

# ---- 4. function
ENV="Variables={TABLE_NAME=$TABLE,RECIPES_TABLE=$RECIPES_TABLE,TV_TABLE=$TV_TABLE,TV_TABLE_REGION=$REGION,BEDROCK_REGION=$REGION}"
if aws lambda get-function --region "$REGION" --function-name "$NAME" >/dev/null 2>&1; then
  aws lambda update-function-code --region "$REGION" --function-name "$NAME" --zip-file "fileb://$ZIP" >/dev/null
  aws lambda wait function-updated --region "$REGION" --function-name "$NAME"
  aws lambda update-function-configuration --region "$REGION" --function-name "$NAME" --environment "$ENV" \
    --timeout 60 --memory-size 512 >/dev/null
  echo "Updated function $NAME"
else
  aws lambda create-function --region "$REGION" --function-name "$NAME" --runtime python3.12 \
    --handler lambda_function.handler --role "$ROLE_ARN" --zip-file "fileb://$ZIP" \
    --timeout 60 --memory-size 512 --environment "$ENV" --tags app=cookalong >/dev/null
  echo "Created function $NAME"
fi
aws lambda wait function-updated --region "$REGION" --function-name "$NAME"
aws lambda put-function-concurrency --region "$REGION" --function-name "$NAME" \
  --reserved-concurrent-executions 3 >/dev/null 2>&1 || echo "(could not reserve concurrency; account limit too low - continuing)"

# ---- 5. public Function URL with CORS for the phone page
CORS="AllowOrigins=$PHONE_ORIGIN,AllowMethods=GET,POST,AllowHeaders=content-type,MaxAge=300"
if aws lambda get-function-url-config --region "$REGION" --function-name "$NAME" >/dev/null 2>&1; then
  aws lambda update-function-url-config --region "$REGION" --function-name "$NAME" --cors "$CORS" >/dev/null
else
  aws lambda create-function-url-config --region "$REGION" --function-name "$NAME" --auth-type NONE --cors "$CORS" >/dev/null
  aws lambda add-permission --region "$REGION" --function-name "$NAME" --statement-id public-url \
    --action lambda:InvokeFunctionUrl --principal "*" --function-url-auth-type NONE >/dev/null
  aws lambda add-permission --region "$REGION" --function-name "$NAME" --statement-id public-url-invoke \
    --action lambda:InvokeFunction --principal "*" --invoked-via-function-url >/dev/null 2>&1 || true
fi
URL=$(aws lambda get-function-url-config --region "$REGION" --function-name "$NAME" --query FunctionUrl --output text)
echo
echo "Fridge-scan API: $URL"
