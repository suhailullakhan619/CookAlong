#!/usr/bin/env bash
# Deploys (or updates) the Cook Along Alexa skill backend: Lambda "cookalong-alexa" (alexa_skill.py + tv_bridge.py).
#
#   bash backend/deploy_alexa.sh                                   # before the skill exists
#   SKILL_ID=amzn1.ask.skill.xxxx bash backend/deploy_alexa.sh     # after: only that skill may invoke the function
#
# Alexa can only call Lambda functions in a few regions. It serves India (en-IN) from its Europe endpoint, so the
# function lives in eu-west-1 (Ireland) and talks to the cookalong-tv table and Bedrock in ap-south-1 (Mumbai).
# Run backend/deploy.sh first: it creates the cookalong-tv table.
set -euo pipefail

REGION="${ALEXA_REGION:-eu-west-1}"
DATA_REGION="${DATA_REGION:-ap-south-1}"
NAME="cookalong-alexa"
ROLE="${NAME}-role"
TV_TABLE="cookalong-tv"
SKILL_ID="${SKILL_ID:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build-alexa"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
echo "Account $ACCOUNT, function region $REGION, data region $DATA_REGION"

# ---- 1. package: two files, no dependencies (boto3 ships with the Lambda runtime)
rm -rf "$BUILD" && mkdir -p "$BUILD"
(cd "$HERE" && python -c "
import zipfile
with zipfile.ZipFile('build-alexa/function.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for f in ('alexa_skill.py', 'tv_bridge.py'): z.write(f)
")
ZIP="$BUILD/function.zip"; command -v cygpath >/dev/null && ZIP="$(cygpath -m "$ZIP")"

# ---- 2. execution role (least privilege)
TRUST='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
POLICY=$(cat <<EOF
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["logs:CreateLogGroup","logs:CreateLogStream","logs:PutLogEvents"],
  "Resource":"arn:aws:logs:$REGION:$ACCOUNT:log-group:/aws/lambda/$NAME*"},
 {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem","dynamodb:UpdateItem","dynamodb:DeleteItem"],
  "Resource":"arn:aws:dynamodb:$DATA_REGION:$ACCOUNT:table/$TV_TABLE"},
 {"Effect":"Allow","Action":["bedrock:InvokeModel"],"Resource":[
  "arn:aws:bedrock:$DATA_REGION:$ACCOUNT:inference-profile/*amazon.nova-*",
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

# ---- 3. function
ENV="Variables={ALEXA_SKILL_ID=$SKILL_ID,TV_TABLE=$TV_TABLE,TV_TABLE_REGION=$DATA_REGION,BEDROCK_REGION=$DATA_REGION}"
if aws lambda get-function --region "$REGION" --function-name "$NAME" >/dev/null 2>&1; then
  aws lambda update-function-code --region "$REGION" --function-name "$NAME" --zip-file "fileb://$ZIP" >/dev/null
  aws lambda wait function-updated --region "$REGION" --function-name "$NAME"
  aws lambda update-function-configuration --region "$REGION" --function-name "$NAME" --environment "$ENV" \
    --timeout 10 --memory-size 256 >/dev/null
  echo "Updated function $NAME"
else
  aws lambda create-function --region "$REGION" --function-name "$NAME" --runtime python3.12 \
    --handler alexa_skill.handler --role "$ROLE_ARN" --zip-file "fileb://$ZIP" \
    --timeout 10 --memory-size 256 --environment "$ENV" --tags app=cookalong >/dev/null
  echo "Created function $NAME"
fi
aws lambda wait function-updated --region "$REGION" --function-name "$NAME"

# ---- 4. let Alexa invoke it (locked to the skill once SKILL_ID is known)
aws lambda remove-permission --region "$REGION" --function-name "$NAME" --statement-id alexa-skill >/dev/null 2>&1 || true
if [ -n "$SKILL_ID" ]; then
  aws lambda add-permission --region "$REGION" --function-name "$NAME" --statement-id alexa-skill \
    --action lambda:InvokeFunction --principal alexa-appkit.amazon.com --event-source-token "$SKILL_ID" >/dev/null
  echo "Only skill $SKILL_ID may invoke $NAME"
else
  aws lambda add-permission --region "$REGION" --function-name "$NAME" --statement-id alexa-skill \
    --action lambda:InvokeFunction --principal alexa-appkit.amazon.com >/dev/null
  echo "Any Alexa skill may invoke $NAME for now: re-run with SKILL_ID=... once the skill exists"
fi
echo
echo "Skill endpoint (Lambda ARN): arn:aws:lambda:$REGION:$ACCOUNT:function:$NAME"
