"""Command-line entry point for construction smoke checks."""

from .application import create_app


def main() -> None:
    app = create_app()
    print(f"{app.title} skeleton constructed")


if __name__ == "__main__":
    main()
