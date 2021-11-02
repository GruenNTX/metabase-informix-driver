# metabase-informix-driver
Driver for connecting IBM Informix DB to metabase

Works with IBM Informix Version 12.10

Also needed in the /metabase/plugins/ is the ifxjdbc.jar
Download ifxjdbc.jar von:
https://www.ibm.com/de-de/products/informix/developer-tools

For instructions on how to install the IBM Informix JDBC Driver:
https://www.ibm.com/docs/en/informix-servers/12.10?topic=driver-files-in-informix-jdbc

### IMPORTANT!
Build with tools.deps/tools.build/Depstar
release v.1.2.0 for Metabase v0.41

Build with leiningen
release v.1.1.1 for Metabase v0.40   
release v.1.0.0 for Metabase v0.37

issue: Mega sync performance improvements #13746   
https://github.com/metabase/metabase/pull/13746

## IMPORTANT 2
this is a work in progress; testing is not completed, so errors may occur when querying an Informix DB in metabase. Feedback is welcome.

## as of Metabase v0.41.0 BUILD WITH tools.deps/tools.build/Depstar

## HOW TO BUILD:

## Prereqs: Install Metabase locally

## Prereq: Install the Clojure CLI

Make sure you have the `clojure` CLI version `1.10.3.933` or newer installed; you can check this with `clojure
--version`. Follow the instructions at https://clojure.org/guides/getting_started if you need to install a
newer version.

## customize /metabase-informix-driver-1.2.0/deps.edn
  :exec-args  {:driver      :ibminformix
                :project-dir "/PATH/TO/metabase-informix-driver-1.2.0"
                :target-dir  "/PATH/TO/metabase-informix-driver-1.2.0/target"}}}}

Make sure to reference to your Metabase-Version in :extra-deps
  {:extra-deps {metabase/metabase-core {:local/root "/PATH/TO/metabase-0.41.0"}
                metabase/build-drivers {:local/root "/PATH/TO/metabase-0.41.0/bin/build-drivers"}}

## Build it

```sh
clojure -X:dev:build
```

will create `target/ibminformix.metabase-driver.jar`. Copy this file to `/path/to/metabase/plugins/` and restart your
server, and the driver will show up.

### Copy it to your plugins dir and restart Metabase

```bash
cp target/uberjar/ibminformix.metabase-driver.jar /path/to/metabase/plugins/
java -jar /path/to/metabase/metabase.jar
```



################## DEPRECATED #########################
## HOW TO BUILD WITH LEININGEN:

### Prereqs: Install Metabase locally, compiled for building drivers

```bash
cd /path/to/metabase/source
lein install-for-building-drivers
```

### Build it

```bash
lein clean
DEBUG=1 LEIN_SNAPSHOTS_IN_RELEASE=true lein uberjar
```

### Copy it to your plugins dir and restart Metabase

```bash
cp target/uberjar/informix.metabase-driver.jar /path/to/metabase/plugins/
jar -jar /path/to/metabase/metabase.jar
```
##########################################################
