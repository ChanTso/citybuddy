# Offline retail policy publication

Publish local retail policies through the same Java draft, publication and Outbox transaction
used by Commerce. This command needs a migrated database and the existing `commerce_app`
runtime grants. It does not run migrations or delete earlier FAQ versions.

Prepare a JSON file containing 1–100 distinct records:

```json
[
  {
    "faqId": "retail-policy-returns",
    "question": "How do I request a refund?",
    "answer": "Open an eligible paid order and confirm its prepared refund request."
  }
]
```

Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` from the deployment's existing private environment source.
Use the `commerce_db` database and `commerce_app` account. Do not pass credentials as command
arguments or place the private environment file in the repository.

After `./mvnw -pl commerce-service -am package`:

```sh
java -Dloader.main=io.citybuddy.commerce.faq.FaqFixturePublisherCli \
  -cp commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher < retail-policies.json
```

The command returns a JSON array of `{faqId,publishedVersion,changed,eventId}` sorted by ID.
An unchanged published record has `changed:false` and `eventId:null`. Changing published
content creates a new version; a different pending draft rejects the whole batch and requires
the operator to resolve that draft before retrying. A failed connection or lost process output
can be retried with the same file: committed identical content creates no further versions.

Exit status is 0 for success, 2 for arguments/input/configuration, 3 for a publication conflict
or validation rejection, 4 for a database failure, and 5 for output serialization failure.
Failure output contains a category rather than connection details or policy text. Publication
makes the SQL policy source available immediately; delivery of its Outbox event and indexing
remain separate asynchronous operations.
