"""Command-line entry point for construction smoke checks."""

from .worker import create_worker


def main() -> None:
    worker = create_worker()
    print(f"{worker.settings.service_name} skeleton constructed")


if __name__ == "__main__":
    main()
