#!/usr/bin/env bash
# Creates or updates the Cook Along Alexa skill in your Alexa developer account with the ASK CLI.
#
#   npm install -g ask-cli && ask configure     # once: sign in with your Amazon developer account
#   bash backend/deploy_alexa.sh                # the skill Lambda (eu-west-1) must exist first
#   bash alexa/deploy_skill.sh                  # creates the skill and builds its language models
#   SKILL_ID=amzn1.ask.skill.... bash backend/deploy_alexa.sh    # lock the Lambda to that skill
#
# The skill package in this repo has a placeholder endpoint (__LAMBDA_ARN__) so no AWS account id is committed. This
# script fills it in from your current AWS identity in a temporary copy, adds the en-IN and en-GB models (copies of
# en-US) and runs `ask deploy`. Test it without a device: python tools/alexa_sim.py "tell cook along next step"
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REGION="${ALEXA_REGION:-eu-west-1}"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cp -r "$HERE/skill-package" "$TMP/skill-package"
python - "$TMP" "arn:aws:lambda:$REGION:$ACCOUNT:function:cookalong-alexa" <<'EOF'
import json
import shutil
import sys

root, arn = sys.argv[1], sys.argv[2]
path = root + "/skill-package/skill.json"
manifest = json.load(open(path, encoding="utf-8"))
manifest["manifest"]["apis"]["custom"]["endpoint"]["uri"] = arn
json.dump(manifest, open(path, "w", encoding="utf-8"), indent=2)
models = root + "/skill-package/interactionModels/custom/"
for locale in ("en-IN", "en-GB"):
    shutil.copy(models + "en-US.json", models + locale + ".json")
json.dump({"askcliResourcesVersion": "2020-03-31",
           "profiles": {"default": {"skillMetadata": {"src": "./skill-package"}}}},
          open(root + "/ask-resources.json", "w"), indent=2)
EOF

cd "$TMP"
ask deploy --target skill-metadata
echo
echo "Next: lock the Lambda to the skill id printed above:"
echo "  SKILL_ID=<that id> bash backend/deploy_alexa.sh"
echo "Then enable it for testing: ask smapi set-skill-enablement -s <that id> -g development"
