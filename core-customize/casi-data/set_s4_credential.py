"""Set the CASI Shop passwords on any CCv2 environment known to CommerceCommander. Run it yourself:
it asks for the passwords (nothing is read from files, nothing secret is printed or stored).

1. BasicCredential `casiS4hcBE6Credential` (the S/4 BE6 communication user, default LC_API_USER),
   used by the consumed destinations from 06_s4_destinations.groovy.
2. Optionally the storefront password of the CASI buyer (casi.buyer@example.com).

usage:  python set_s4_credential.py [alias]        alias = CommerceCommander system, default demoland
        python set_s4_credential.py demoland --buyer
"""
import argparse
import base64
import getpass
import sys
from pathlib import Path

try:
    from commander import systems
except ImportError:  # CommerceCommander checked out next to this repo (…/GitHub/CommerceCommander)
    sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "CommerceCommander"))
    from commander import systems

CREDENTIAL_ID = "casiS4hcBE6Credential"
BUYER = "casi.buyer@example.com"


def ask(prompt: str) -> str:
    while True:
        first = getpass.getpass(prompt + ": ")
        if not first:
            print("  empty, try again")
            continue
        if getpass.getpass(prompt + " (again): ") == first:
            return first
        print("  did not match, try again")


def enc(text: str) -> str:
    # Values travel base64-encoded: CCv2's XSS filter rewrites some request text.
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description="Set the CASI Shop S/4 credential (and optionally the buyer password); asks for the passwords.")
    parser.add_argument("alias", nargs="?", default="demoland", help="CommerceCommander system (default demoland)")
    parser.add_argument("--user", default="LC_API_USER", help="S/4 communication user (default LC_API_USER)")
    parser.add_argument("--buyer", action="store_true", help=f"also set the password of {BUYER}")
    args = parser.parse_args()

    print(f"Target: {args.alias}. Credential {CREDENTIAL_ID}, username {args.user}.")
    script = f"""
def dec = {{ s -> new String(Base64.getDecoder().decode(s), 'UTF-8') }}
def cred = flexibleSearchService.search("SELECT {{pk}} FROM {{BasicCredential}} WHERE {{id}}='{CREDENTIAL_ID}'").result[0] ?: modelService.create('BasicCredential')
cred.id = '{CREDENTIAL_ID}'
cred.username = dec('{enc(args.user)}')
cred.password = dec('{enc(ask("S/4 password for " + args.user))}')
modelService.save(cred)
println 'saved {CREDENTIAL_ID} for ' + cred.username
"""
    if args.buyer:
        script += f"""
def buyer = userService.getUserForUID('{BUYER}')
userService.setPassword(buyer, dec('{enc(ask("Storefront password for " + BUYER))}'), 'argon2')
modelService.save(buyer)
println 'password set for ' + buyer.uid + ' (' + buyer.passwordEncoding + ')'
"""
    result = systems.run_ephemeral(args.alias, "run_groovy", script, commit=True, timeout=120)
    print((result.get("outputText") or "").strip())
    if result.get("stacktraceText"):
        print("FAILED:", str(result["stacktraceText"]).splitlines()[0][:300])
        sys.exit(1)


if __name__ == "__main__":
    main()
