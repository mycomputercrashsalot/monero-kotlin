# Testing

The tests are JUnit 5 tests in [src/test/kotlin](../src/test/kotlin). There are two kinds:

* **Unit tests** need no node. They read and write the JSON of the models, which is the JSON that monero-c writes, in [src/test/resources/fixtures](../src/test/resources/fixtures). The ones that call monero-c, e.g. the utils, are skipped when its library is not found.
* **Integration tests** are tagged `integration`. They need monero-c, a regtest node and wallet RPC servers, and they are not run by `./gradlew test`.

The tests mirror the ones of [monero-python](https://github.com/libmonero/monero-python), so a behavior that one of them pins is the same in both.

## Unit tests

```sh
./gradlew test
```

To run the ones that call monero-c, set the environment variable `MONERO_C_LIBRARY` to the library, built or downloaded as described in [Building](building.md#getting-monero-c):

```sh
MONERO_C_LIBRARY=/path/to/monero-kotlin/external/monero-c/build/libmonero_c.so ./gradlew test --rerun
```

Gradle does not run the tests again when only the environment changed, so add `--rerun` after setting or unsetting the variable.

## Integration tests

They need monero-c and the regtest nodes and wallet RPC servers of [src/test/docker/docker-compose.yml](../src/test/docker/docker-compose.yml), which are the ones of monero-python: two nodes that are connected to each other and three wallet RPC servers, with the same login. It needs Docker and ports 18080 to 18086, 18089 and 48080:

```sh
docker compose -f src/test/docker/docker-compose.yml up -d node_1 node_2 xmr_wallet_1 xmr_wallet_2 xmr_wallet_3
MONERO_C_LIBRARY=/path/to/monero-kotlin/external/monero-c/build/libmonero_c.so ./gradlew integrationTest --rerun
docker compose -f src/test/docker/docker-compose.yml down -v
```

[bin/setup_test_environment.sh](../bin/setup_test_environment.sh) starts the same containers and waits until the node and the servers answer, and [bin/cleanup_test_environment.sh](../bin/cleanup_test_environment.sh) removes them with their volumes. `COMPOSE_FILE` and `COMPOSE_PROJECT_NAME` change the compose file and the project of both.

### Nodes and servers

The tests use the second node, at `http://127.0.0.1:18081` with the user `rpc_daemon_user`, and the three wallet RPC servers, at `http://127.0.0.1:18082` and the two ports that follow, with the user `rpc_user`. The password of both is `abc123`.

A server has one open wallet, so the first one holds the wallet that the tests share, and the wallets that a test makes are on the other two. The tests skip when nothing answers, and fail when the server does not accept the login.

| Variable | What it changes |
| --- | --- |
| `MONERO_C_TEST_DAEMON_URI` | the URI of the daemon |
| `MONERO_C_TEST_DAEMON_USERNAME` | the username of the daemon |
| `MONERO_C_TEST_DAEMON_PASSWORD` | the password of the daemon |
| `MONERO_C_TEST_WALLET_RPC_URI` | the URI of the first wallet RPC server |
| `MONERO_C_TEST_WALLET_RPC_USERNAME` | the username of the wallet RPC servers |
| `MONERO_C_TEST_WALLET_RPC_PASSWORD` | the password of the wallet RPC servers |
| `MONERO_TAG` | the tag of the images of the containers |

The images are `ghcr.io/libmonero/monero-daemon-rpc` and `ghcr.io/libmonero/monero-wallet-rpc`. The nodes have a difficulty of 1, since the tests mine hundreds of blocks with `generateblocks`.

## Logs

The tests log what they run, the way the tests of monero-python do: to the console as they go, and to `build/test-logs/monero_tests_kotlin.log`. The log of monero-c goes to `build/test-logs/monero_tests_cpp_<date>.log`, at the level 0 of the library.

| Variable | What it changes |
| --- | --- |
| `MONERO_C_TEST_LOG_LEVEL` | the level of the log of monero-c, 0 to 4 |
| `MONERO_C_TEST_LOG_CATEGORIES` | the categories of the log of monero-c |
| `MONERO_KOTLIN_TEST_LOG_DIR` | the directory of the files of the log |

Gradle shows the console log of each test with its result, and the console launcher of the CI shows it too, with the files in the results that it uploads.

## Light wallet tests

The light wallet tests need monero-c with the light wallet surface and a regtest node with a monero-lws server, which is the [docker-compose.yml](../src/test/integration/docker-compose.yml) of the integration tests:

```sh
docker compose -f src/test/integration/docker-compose.yml up -d
MONERO_C_LIBRARY=/path/to/monero-kotlin/external/monero-c/build/libmonero_c.so ./gradlew integrationTest --rerun
docker compose -f src/test/integration/docker-compose.yml down -v
```

The node is expected at `http://127.0.0.1:18081`: set the environment variable `MONERO_C_TEST_DAEMON_URI` for another one. The monero-lws server is expected at `http://127.0.0.1:8443`: set the environment variable `MONERO_KOTLIN_TEST_LWS_URI` for another one. The light wallet tests need a monero-c with the light wallet surface, which the submodule does not have yet: they skip with a message when the library has no light wallet.

## Coverage

```sh
./gradlew test jacocoTestReport
```

The report is in `build/reports/jacoco/test`: `html/index.html`, and `jacocoTestReport.xml`, which is the one that Codacy reads.

## Running the tests without building

`./gradlew unitTests` collects the classes of the tests, their resources and their libraries in `build/unit-tests`, which the CI runs on every platform without building anything. See [Continuous integration](continuous-integration.md).
