"""Generate a one-use BCrypt verifier for synthetic integration credentials."""

import argparse

import bcrypt


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("credential")
    args = parser.parse_args()
    print(bcrypt.hashpw(args.credential.encode(), bcrypt.gensalt(rounds=12)).decode())


if __name__ == "__main__":
    main()
