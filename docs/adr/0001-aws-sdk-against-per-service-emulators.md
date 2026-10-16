# Real AWS SDK against per-service local emulators, not LocalStack

Services talk to AWS through the real AWS SDK v2 clients (via Quarkiverse Amazon Services), pointed at free local emulators that each run in their own container: DynamoDB Local (`amazon/dynamodb-local`) for DynamoDB, ElasticMQ (`softwaremill/elasticmq-native`) for SQS, and Redis standing in for ElastiCache. One container per AWS service is what lets us stop "DynamoDB" without also stopping SQS, which the Blast Radius lesson depends on. Switching to real AWS is an endpoint change only.

## Considered Options

- **LocalStack.** Since March 2026 the image refuses to start without an auth token, the free Hobby plan forbids commercial use and is licensed per user (every student would need an account), ElastiCache and LocalStack's own Chaos API are paid-only, and a single container serves every AWS API. Rejected for an offline, clone-and-run lecture.
- **Hand-written fake AWS Services.** Keeps everything in our code, but students would learn our fake API instead of the AWS SDK they will meet at work.
- **Quarkus Dev Services (Floci by default).** Shares one container across all AWS services and starts outside Docker Compose, invisible to the dashboard. Dev Services are disabled; Compose is the only source of infrastructure.
