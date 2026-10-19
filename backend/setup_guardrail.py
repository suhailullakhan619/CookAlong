"""Creates (or finds) the Amazon Bedrock Guardrail used by CookAlong's AI features and prints "<id> <version>".

    python backend/setup_guardrail.py            # region from AWS_REGION / ap-south-1

What it enforces on the dinner-planner agent and the Alexa "ask the chef" answers:
  - content filters (hate, insults, sexual, violence, misconduct) and prompt-attack detection on the input,
  - a denied topic for medical advice (allergy treatment, diagnoses, medication): the chef gives cooking help only,
  - personal data (emails, phone numbers) is masked in anything the model sees or says.
Idempotent: if a guardrail with this name exists, its id and latest version are printed instead.
"""
import os
import sys

import boto3

NAME = "cookalong-kitchen"
REGION = os.environ.get("AWS_REGION", "ap-south-1")
BLOCKED = "Sorry, I can only help with cooking. For health or allergy questions, please check with a doctor or the food label."


def main():
    client = boto3.client("bedrock", region_name=REGION)
    for g in client.list_guardrails()["guardrails"]:
        if g["name"] == NAME:
            print(g["id"], latest_version(client, g["id"]) or version_of(client, g["id"]))
            return
    created = client.create_guardrail(
        name=NAME,
        description="Keeps CookAlong's AI on cooking: no medical advice, no abuse, no prompt attacks, no personal data.",
        topicPolicyConfig={"topicsConfig": [{
            "name": "medical-advice",
            "definition": "Diagnosing or treating medical conditions, advising on medication, or saying a food is safe or "
                          "unsafe for an allergy, illness or pregnancy.",
            "examples": [
                "Is this recipe safe for my peanut allergy?",
                "What medicine should I take for food poisoning?",
                "Can I eat this while pregnant?",
            ],
            "type": "DENY",
        }]},
        contentPolicyConfig={"filtersConfig": [
            {"type": t, "inputStrength": "MEDIUM", "outputStrength": "MEDIUM"}
            for t in ("SEXUAL", "VIOLENCE", "HATE", "INSULTS", "MISCONDUCT")
        ] + [{"type": "PROMPT_ATTACK", "inputStrength": "HIGH", "outputStrength": "NONE"}]},
        sensitiveInformationPolicyConfig={"piiEntitiesConfig": [
            {"type": "EMAIL", "action": "ANONYMIZE"},
            {"type": "PHONE", "action": "ANONYMIZE"},
        ]},
        blockedInputMessaging=BLOCKED,
        blockedOutputsMessaging=BLOCKED,
        tags=[{"key": "app", "value": "cookalong"}],
    )
    print(created["guardrailId"], version_of(client, created["guardrailId"]))


def latest_version(client, guardrail_id):
    """The newest published (numbered) version, or None if only the draft exists."""
    versions = [g["version"] for g in client.list_guardrails(guardrailIdentifier=guardrail_id)["guardrails"] if g["version"] != "DRAFT"]
    return max(versions, key=int) if versions else None


def version_of(client, guardrail_id):
    """Publishes the current draft as an immutable version (needed to attach it to a model call)."""
    return client.create_guardrail_version(guardrailIdentifier=guardrail_id, description="CookAlong release")["version"]


if __name__ == "__main__":
    sys.exit(main())
