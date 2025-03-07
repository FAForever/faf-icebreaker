# faf-icebreaker

The FAF icebreaker is a fully functional WebRTC signalling server aimed at the needs for the FAForever project.

It has two main features:
* It provides a list to TURN and STUN servers with session credentials both static (self-hosted coturn) and dynamically (Xirsys, Cloudflare). 
* It provides a simple signalling mechanism, for sending events via REST POST and receiving events as a stream via SSE.

For authentication, you first need to acquire a session token for a specific gameId. This is secured via OIDC tokens from the configured OIDC server (by default: hydra.faforever.com aka FAF production). In the response you get a self-signed JWT session token. This session token can be used for all endpoints.

The available environment variables for configuration can be found in `src/main/resources/application.yaml`. Variables are declared like `${ENV_VARIABLE_NAME:fallback-value}`.

This application makes use of the [GeoLite2](https://dev.maxmind.com/geoip/geolite2-free-geolocation-data) database to search for the closest Xirsys server according to the users ip address. The file must be present on startup or all requests will fall back to Frankfurt, Germany.

## Testing without a real OIDC provider

Since all calls can use the self-signed JWT for authentication, we can make use of the default/dev certificates and use pre-generated tokens for test (even for obtaining a new token!).


<details>

<summary>The following tokens can be used for testing for game id 100</summary>

* **User ID 1:** eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIiwiZXh0Ijp7InJvbGVzIjpbIlVTRVIiXSwiZ2FtZUlkIjoxMDB9LCJzY3AiOlsibG9iYnkiXSwiaXNzIjoiaHR0cHM6Ly9pY2UuZmFmb3JldmVyLmNvbSIsImF1ZCI6Imh0dHBzOi8vaWNlLmZhZm9yZXZlci5jb20iLCJleHAiOjIwMDAwMDAwMDAsImlhdCI6MTc0MTAwMDAwMCwianRpIjoiMDE5YjBmMDYtOGJlYi00NzEyLWFiNWUtNGUyNmVjMTM0YjFlIn0.CHEtH0I-BacvjIc_a8ZSKcXMmRZqObGIqScs8BNbZrcje9GVvnTeJEkOxh3Lpo0C1Cm8_x_YQ-zilMTmVu87ZH31_FRYvJuaU9gjo3izmHcncWmSOpjg2n8BtkPXcnggdxM5DW7bPUytkgPGhvFUbeTNRw0Lv1Atb9L2NcW33jhQ-jz-3Ev0fVfgAzJMxrhDCpoCw4QMk6doEIbmJ0Egl1-9AHyr3jd1PXMQAI2K3dX2v0hUmOJ2MxClukUFXkXRp76ZJ9L594YU1gLlIprcuPtRQCIvgJ_gD2Cd6iPQHAUFFvNFmpyLVDU3fgrznWIRkcu2CWSlybhFHCvx5Eldhg 
* **User ID 2:** eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIyIiwiZXh0Ijp7InJvbGVzIjpbIlVTRVIiXSwiZ2FtZUlkIjoxMDB9LCJzY3AiOlsibG9iYnkiXSwiaXNzIjoiaHR0cHM6Ly9pY2UuZmFmb3JldmVyLmNvbSIsImF1ZCI6Imh0dHBzOi8vaWNlLmZhZm9yZXZlci5jb20iLCJleHAiOjIwMDAwMDAwMDAsImlhdCI6MTc0MTAwMDAwMCwianRpIjoiZmNkOTkwZjYtNWU3Mi00MjA4LTg1MzktNmQ1NDU3NDkyOTY4In0.Ef0LJlcziNPaq2OHSUXaHWvDMt6hx42qa9IUDdAUmReluGz0XNPmpJqtbXk2b5uoAZ2s1dS8DQ0axtfjtKRgH9sElnZW1uFahNOYNM8rDYKE9gw2oXUkJQwg-orvOUaSZncX0YNehtJmSzYA7PpbhLiJ9yvroamQ2XqjfcOZ15iz0hsLJ5HM8kB0x09zVDQdncelaqatVLMRRL1xm7PZyavp39yca8kvuk98_IylJtDi0SfkShx-fRoKMBDu9bwqgv8ldpIkN6-x6yuSv_Clo8i7ct7Np8lDhUFDv3mQsbCgEt5FUYTSXplxTO84R7dnwUfFNfVn7qrQqxhpsij9NQ
* **User ID 3:** eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIzIiwiZXh0Ijp7InJvbGVzIjpbIlVTRVIiXSwiZ2FtZUlkIjoxMDB9LCJzY3AiOlsibG9iYnkiXSwiaXNzIjoiaHR0cHM6Ly9pY2UuZmFmb3JldmVyLmNvbSIsImF1ZCI6Imh0dHBzOi8vaWNlLmZhZm9yZXZlci5jb20iLCJleHAiOjIwMDAwMDAwMDAsImlhdCI6MTc0MTAwMDAwMCwianRpIjoiYmY2MGQwNDQtOTBiNi00NDFkLTkxZTAtMWIyZTI4MzRmMWM2In0.UiIkshBOj-yto1h4ibMkPQU0zHtqF7EIonOFf6mifpJ-KwXGXGDIeWBq1MCNOV7hfDqk1Gd8eQuV0KNayJxlD2Y_CEnm-BlUraUPi_U7_Af1qpMU8ttSP7cMZcauhc1shTVk6bcjL-3CTy80B7f_03GnbYt5jpFeoah82cO38syfJHEpNp1MX390RGiwzLp13nmSxJCC9CNb2iQlXnN5GuyVVJ2hYTB2bjo5idJZx4q649kR347WMwuUDPI6up7EhWDIDivOweniXKb-ZHvRenGORvlBO07OttwuSCjgE2vNbcCV6ioYa7AF1Rc-4k9ND39M2rwnaIDCHfMjrckKaw
* **User ID 4:** eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJzdWIiOiI0IiwiZXh0Ijp7InJvbGVzIjpbIlVTRVIiXSwiZ2FtZUlkIjoxMDB9LCJzY3AiOlsibG9iYnkiXSwiaXNzIjoiaHR0cHM6Ly9pY2UuZmFmb3JldmVyLmNvbSIsImF1ZCI6Imh0dHBzOi8vaWNlLmZhZm9yZXZlci5jb20iLCJleHAiOjIwMDAwMDAwMDAsImlhdCI6MTc0MTAwMDAwMCwianRpIjoiZWZiOWQwYjQtODk1Yi00ZjBmLTk5YmYtZjIyOGEzNjQ5NjRmIn0.ge7UpSSq_6m851VzlkyVya8PM2Yoai0euieOO5C_AGht7tAIhIMvsZ8tPCG5X3DTLO9gKZtdokDlQ8QNwrCzzEKce63V2u33NQ_napnvt-g3ucP8FZHyA_h7gNqriCPom0J5sPo6jG_EMdz3YRe8aM2H-CL61lHp32npYuBAeGPGxswPeWprXGgWCp_jenUOYHEWI62hweRo7GP3HO4ksczT0dcrK6VVFoEWpsxHueeTkTD1gJff5Cl0HFSfYkmsTGeURmiC2qgO7dEjSzGT3hTt_mCXUHf21VWOWfnCk0lsYZQ8FSP6uWJQhDj16qiA2sIJwONhauMzNykbSkyKjg
</details>

## Running the application in dev mode

This application needs MariaDB and RabbitMQ to run. You can use our docker-compose file. 
It will set up default users and passwords, so that no further configuration is required.

```shell script
docker-compose up -d
```


You can run your application in dev mode that enables live coding using:
```shell script
./gradlew quarkusDev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev/.

## Packaging and running the application

The application can be packaged using:
```shell script
./gradlew build
```
It produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `build/quarkus-app/lib/` directory.

The application is now runnable using `java -jar build/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:
```shell script
./gradlew build -Dquarkus.package.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar build/*-runner.jar`.
