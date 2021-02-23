# metabase-informix-driver
Driver for connecting IBM Informix DB to metabase

Works with IBM Informix Version 12.10.UC2

### IMPORTANT!
Works with metabase Versions OLDER than v0.37

Metabase v037newer versions of Metabase break the driver, because of src/metabase/driver/sql_jdbc/sync/common.clj:
```bash
 (defn simple-select-probe-query
  "Simple (ie. cheap) SELECT on a given table to test for access and get column metadata. By default doesn't return
  anything useful (only used to check whether we can execute a SELECT query) but you can override this by passing
  `clause-overrides`.
SELECT true FROM table WHERE 1 <> 1 LIMIT 0
```
Informix breaks on "LIMIT 0"

Unfortunately I couldn't yet fix this...

issue: Mega sync performance improvements #13746   
https://github.com/metabase/metabase/pull/13746

## IMPORTANT 2
this is a work in progress; testing is not completed, so errors may occur when querying an Informix DB in metabase. Feedback is welcome.

## HOW TO BUILD:

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

