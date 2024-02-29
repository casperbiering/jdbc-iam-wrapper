# jdbc-iam-wrapper

## Features
* Short connection url with username and profile
* Translates AWS Profile written in the password field into the AWS IAM RDS token
* Based on AWS SDK v2 to better support local profiles
* Forces SSL connection and embeds the [global-bundle.pem](https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem) as trustCertificateKeyStore (cached from 2024-02-28).

## Setup

### IDEs

WIP

Connection URLs examples:
* `jdbc:iam:mysql://USERNAME:AWS_PROFILE@HOSTNAME`
* `jdbc:iam:mysql://HOSTNAME?user=USERNAME&password=AWS_PROFILE`
* `jdbc:iam:mysql://HOSTNAME` (and provide username and password through the UI)

### Gradle

WIP

## Properties

Properties for the wrapper can be specified either as URL query parameters or as regular JDBC connection properties. If the same property is specified on both, then the URL query parameter will take precedence. If user and password is specified separately it will take precedence.

All properties are passed to the delegate JDBC driver, except from the following is the list of wrapper specific properties:

loadJdbcDriverClass

awsRegion

## Attributions

This is heavily based on [magJ/iam-jdbc-driver](https://github.com/magJ/iam-jdbc-driver) and a AWS SDK v2 [code example](https://github.com/aws/aws-sdk-java-v2/issues/1157#issuecomment-561677354).
