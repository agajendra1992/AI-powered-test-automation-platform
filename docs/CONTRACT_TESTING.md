# Contract testing (Pact)

Consumer-driven contracts between this framework's API layer (**consumer** `qa-platform-framework`)
and the QA Shop API (**provider** `qa-shop-api`), with [Pact JVM](https://docs.pact.io/implementation_guides/jvm)
4.7. Only Pact's core modules are used (no JUnit); the tests run on TestNG.

## Why

Functional API tests check that the running API works today. A contract checks that the provider
still gives every consumer what it relies on, without running the whole system. It catches breaking
changes (a renamed or removed field, a changed status code) early, on the provider's side.

## How it works

| Step | Class | What happens |
|---|---|---|
| 1. Consumer | `contract/QaShopApiConsumerPactTest` | The framework's real `AuthService` and `UserService` call Pact's mock server. Each interaction states the request and the smallest response the framework needs, with type and regex matchers (not exact values). On success Pact writes `target/pacts/qa-platform-framework-qa-shop-api.json`. |
| 2. Provider | `contract/QaShopApiProviderPactTest` | Replays every interaction against the real API (`api.base.url`), calling `/_pact/provider-states` first to set up each state, and fails on any mismatch. |

Interactions: sign-in; get an existing user (state: the user exists); get a missing user (404);
create a user (state: the email is free).

**Credentials** never enter the pact: the consumer uses example values, and at replay time a
request filter swaps in the configured account and a fresh token.

**Provider states**: the demo app accepts `POST /_pact/provider-states` only when started with
`PACT_PROVIDER_STATES=1`; normal runs do not have the endpoint.

## Running

```bash
# Provider with state set-up enabled
PACT_PROVIDER_STATES=1 python -m uvicorn app:app --app-dir sut --port 8000
# Consumer, then provider verification (suite keeps the order)
mvn test -DsuiteXmlFile=testng-contract.xml
```

CI: job **Contract tests** in `ci.yml` on every PR; the pact file is uploaded as the `pacts` artifact.

## With a Pact Broker (not set up)

In a multi-team setup the consumer publishes its pact to a Pact Broker (or PactFlow), the provider's
pipeline verifies the latest pacts and publishes results, and `can-i-deploy` gates releases. This
repository has one consumer and one provider in the same repo, so the file is handed over directly.
Adding a broker means: a `pact-broker publish` step after the consumer test, and a `PactBrokerSource`
instead of the `FileSource` in the provider test.

## Verification (2026-11-24)

* Consumer and provider pass; the suite passes twice in a row against the same provider.
* With the provider changed to drop the `job` field, verification failed: "Actual map is missing the following keys: job".
* The pact file contains no configured credentials.
