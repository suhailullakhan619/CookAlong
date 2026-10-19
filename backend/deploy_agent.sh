#!/usr/bin/env bash
# Deploys (or updates) the CookAlong dinner-planner agent: Lambda "cookalong-agent" (dinner_agent.py + Strands SDK).
#
#   bash backend/deploy_agent.sh
#
# Needs: the recipes, scans and TV tables (run backend/deploy.sh first) and the Bedrock guardrail
# (python backend/setup_guardrail.py prints "<id> <version>"; this script runs it for you).
# Creates, in REGION (default ap-south-1):
#   - IAM role   cookalong-agent-role   (CloudWatch logs, read recipes + scans, rate-limit counter in the TV table,
#                                        Bedrock InvokeModel[WithResponseStream] on Amazon Nova, ApplyGuardrail on ours)
#   - Lambda     cookalong-agent        (Python 3.12, 1 GB, 90 s)
#   - Function URL (public; each TV is limited to 12 plans an hour inside the function)
# Prints the URL: put it in app/src/main/assets/config.json -> "agentApiUrl".
set -euo pipefail

REGION="${REGION:-ap-south-1}"
NAME="cookalong-agent"
ROLE="${NAME}-role"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build-agent"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
MODEL_ID="${AGENT_MODEL_ID:-apac.amazon.nova-pro-v1:0}"
echo "Account $ACCOUNT, region $REGION"

# ---- 1. guardrail (idempotent)
read -r GUARDRAIL_ID GUARDRAIL_VERSION < <(python "$HERE/setup_guardrail.py")
echo "Guardrail $GUARDRAIL_ID v$GUARDRAIL_VERSION"

# ---- 2. package: Strands + dependencies built for Lambda's Linux x86_64 / Python 3.12, plus our two files.
# --no-deps with a pinned list: pip on Windows would otherwise try to resolve the Windows-only pywin32 for Linux.
rm -rf "$BUILD" && mkdir -p "$BUILD/pkg"
python -m pip install --quiet --no-deps --target "$BUILD/pkg" --platform manylinux2014_x86_64 --implementation cp \
  --python-version 3.12 --only-binary=:all: -r "$HERE/requirements-agent.txt"
cp "$HERE/dinner_agent.py" "$BUILD/pkg/"
(cd "$BUILD/pkg" && python -c "
import os, zipfile
with zipfile.ZipFile('../function.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, _, files in os.walk('.'):
        if '__pycache__' in root: continue
        for f in files: z.write(os.path.join(root, f))
")
echo "Package: $(du -h "$BUILD/function.zip" | cut -f1)"
ZIP="$BUILD/function.zip"; command -v cygpath >/dev/null && ZIP="$(cygpath -m "$ZIP")"

# ---- 3. execution role (least privilege)
TRUST='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
POLICY=$(cat <<EOF
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["logs:CreateLogGroup","logs:CreateLogStream","logs:PutLogEvents"],
  "Resource":"arn:aws:logs:$REGION:$ACCOUNT:log-group:/aws/lambda/$NAME*"},
 {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:Scan"],
  "Resource":"arn:aws:dynamodb:$REGION:$ACCOUNT:table/cookalong-recipes"},
 {"Effect":"Allow","Action":["dynamodb:GetItem"],"Resource":"arn:aws:dynamodb:$REGION:$ACCOUNT:table/cookalong-scans"},
 {"Effect":"Allow","Action":["dynamodb:UpdateItem","dynamodb:PutItem"],"Resource":"arn:aws:dynamodb:$REGION:$ACCOUNT:table/cookalong-tv"},
 {"Effect":"Allow","Action":["bedrock:InvokeModel","bedrock:InvokeModelWithResponseStream"],"Resource":[
  "arn:aws:bedrock:$REGION:$ACCOUNT:inference-profile/*amazon.nova-*",
  "arn:aws:bedrock:*::foundation-model/amazon.nova-*"]},
 {"Effect":"Allow","Action":["bedrock:ApplyGuardrail"],"Resource":"arn:aws:bedrock:$REGION:$ACCOUNT:guardrail/$GUARDRAIL_ID"}
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
ENV="Variables={BEDROCK_REGION=$REGION,AGENT_MODEL_ID=$MODEL_ID,GUARDRAIL_ID=$GUARDRAIL_ID,GUARDRAIL_VERSION=$GUARDRAIL_VERSION,RECIPES_TABLE=cookalong-recipes,TABLE_NAME=cookalong-scans,TV_TABLE=cookalong-tv}"
if aws lambda get-function --region "$REGION" --function-name "$NAME" >/dev/null 2>&1; then
  aws lambda update-function-code --region "$REGION" --function-name "$NAME" --zip-file "fileb://$ZIP" >/dev/null
  aws lambda wait function-updated --region "$REGION" --function-name "$NAME"
  aws lambda update-function-configuration --region "$REGION" --function-name "$NAME" --environment "$ENV" \
    --timeout 90 --memory-size 1024 >/dev/null
  echo "Updated function $NAME"
else
  aws lambda create-function --region "$REGION" --function-name "$NAME" --runtime python3.12 \
    --handler dinner_agent.handler --role "$ROLE_ARN" --zip-file "fileb://$ZIP" \
    --timeout 90 --memory-size 1024 --environment "$ENV" --tags app=cookalong >/dev/null
  echo "Created function $NAME"
fi
aws lambda wait function-updated --region "$REGION" --function-name "$NAME"

# ---- 5. public Function URL
if aws lambda get-function-url-config --region "$REGION" --function-name "$NAME" >/dev/null 2>&1; then
  :
else
  aws lambda create-function-url-config --region "$REGION" --function-name "$NAME" --auth-type NONE >/dev/null
  aws lambda add-permission --region "$REGION" --function-name "$NAME" --statement-id public-url \
    --action lambda:InvokeFunctionUrl --principal "*" --function-url-auth-type NONE >/dev/null
  aws lambda add-permission --region "$REGION" --function-name "$NAME" --statement-id public-url-invoke \
    --action lambda:InvokeFunction --principal "*" --invoked-via-function-url >/dev/null 2>&1 || true
fi
URL=$(aws lambda get-function-url-config --region "$REGION" --function-name "$NAME" --query FunctionUrl --output text)
echo
echo "Dinner-planner API: $URL"
