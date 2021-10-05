(ns metabase.driver.ibminformix 
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [honeysql.core :as hsql]
            [java-time :as t]
	    [metabase.driver :as driver]
            [metabase.driver.common :as driver.common]
            [metabase.driver.sql :as sql]
            [metabase.driver.sql.query-processor :as sql.qp]
	    [metabase.driver.sql.util :as sql.u]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
	    [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
	    [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
	    [metabase.driver.sql-jdbc.sync.common :as common]
	    [metabase.driver.sql-jdbc.sync.interface :as i]   
 	    [metabase.driver.sql.parameters.substitution :as params.substitution]
            [metabase.driver.sql-jdbc.execute.legacy-impl :as legacy]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.util.date-2 :as du]
	    [metabase.util.honeysql-extensions :as hx]
	    [metabase.util.ssh :as ssh]
	    [schema.core :as s])
  (:import [java.sql Connection DatabaseMetaData ResultSet Types]
           [java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime]
           java.util.Date))

(driver/register! :ibminformix, :parent #{:sql-jdbc ::legacy/use-legacy-classes-for-read-and-set})

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             metabase.driver impls                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod driver/display-name :ibminformix [_] "IBMInformix")

(defmethod driver/humanize-connection-error-message :ibminformix [_ message]
  (condp re-matches message
    #"^FATAL: database \".*\" does not exist$"
    (driver.common/connection-error-messages :database-name-incorrect)

    #"^No suitable driver found for.*$"
    (driver.common/connection-error-messages :invalid-hostname)

    #"^Connection refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.$"
    (driver.common/connection-error-messages :cannot-connect-check-host-and-port)

    #"^FATAL: role \".*\" does not exist$"
    (driver.common/connection-error-messages :username-incorrect)

    #"^FATAL: password authentication failed for user.*$"
    (driver.common/connection-error-messages :password-incorrect)

    #"^FATAL: .*$" ; all other FATAL messages: strip off the 'FATAL' part, capitalize, and add a period
    (let [[_ message] (re-matches #"^FATAL: (.*$)" message)]
      (str (str/capitalize message) \.))

    #".*" ; default
    message))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           metabase.driver.sql impls                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- date-format [format-str expr] (hsql/call :to_char expr (hx/literal format-str)))

(defn- str-to-date [format-str expr] (hsql/call :to_date expr (hx/literal format-str))) 

;;"Truncate a date: (trunc :day v) -> TRUNC(v, 'day')"
(defn- trunc  [format-str expr]
  (hsql/call :trunc expr (hx/literal format-str)))

(defn- trunc-with-format [format-str expr]
  (str-to-date format-str (date-format format-str expr)))

(def ^:private ->dbinfo (partial hsql/call :DBINFO)) 

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                            		metabase.driver.sql impls                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

;;NOTE! in this example, methods for querying time-information like HOUR, SECOND, etc.  are not not included
;;defmethod .. [:day, :week, :month, :year] returns date 
;;defmethod .. [:day-of-week, :day-of-month, :day-of-year, :week-of-year, :month-of-year, :quarter-of-year] returns Integer
;;https://www.ibm.com/support/knowledgecenter/en/SSGU8G_12.1.0/com.ibm.sqls.doc/ids_sqs_1481.htm

(defmethod sql.qp/date [:ibminformix :day]            [_ _ expr] (trunc :dd expr))
 
(defmethod sql.qp/date [:ibminformix :week]           [_ _ expr] (trunc :day expr))

(defmethod sql.qp/date [:ibminformix :month]          [_ _ expr] (trunc :month expr))

;;(defmethod sql.qp/date [:ibminformix :quarter]        [_ _ expr] (str-to-date "%Y-%m-%d" (hsql/raw (format "%d-%d-01" (int (hx/year expr)) (int ((hx/- (hx/* (hx/quarter expr) 3) 2))))))) 

(defmethod sql.qp/date [:ibminformix :year]           	[_ _ expr] (trunc :year expr))

(defmethod sql.qp/date [:ibminformix :day-of-week]	[driver _ expr] (hsql/call :day_of_week expr))

(defmethod sql.qp/date [:ibminformix :day-of-month]   	[_ _ expr] (hsql/call :day expr)) 

(defmethod sql.qp/date [:ibminformix :day-of-year]     	[_ _ expr] (hsql/call :year expr))

(defmethod sql.qp/date [:ibminformix :quarter-of-year] 	[driver _ expr] (hsql/call :quarter expr))

(defmethod sql.qp/date [:ibminformix :week-of-year]	[driver _ expr] expr)

(defmethod sql.qp/date [:ibminformix :month-of-year]	[driver _ expr] expr)

(defmethod sql.qp/add-interval-honeysql-form :ibminformix [_ _ amount unit] 
      (case unit 
		:day     	(hsql/raw (format "CAST(CURRENT as datetime year to day) + %d UNITS day"		(int amount)))
		:week    	(hsql/raw (format "CAST(CURRENT as datetime year to day) + %d * 7 UNITS day"		(int amount))) 
		:week_of_year 	(hsql/raw (format "CAST(CURRENT as datetime year to day) + %d * 52 UNITS week"		(int amount)))
		:month   	(hsql/raw (format "CAST(CURRENT as datetime year to day) + %d UNITS month"		(int amount)))
		:quarter 	(hsql/raw (format "CAST(CURRENT as datetime year to day) + %d * 3 UNITS month"		(int amount)))
		:year    	(hsql/raw (format "CAST(CURRENT as datetime year to day) + %d UNITS year" 		(int amount)))
		:day-of-month	(hsql/raw (format "CAST(CURRENT as datetime year to day) + %d UNITS day"		(int amount)))
	)
)

;;see ://github.com/metabase/metabase/blob/master/modules/drivers/sqlite/src/metabase/driver/sqlite.clj
(defmethod sql.qp/unix-timestamp->honeysql [:ibminformix :seconds] 
  [_ _ expr]
  (->dbinfo (hx/literal "utc_to_datetime") expr)) 

(def ^:private now (hsql/raw "CURRENT")) 

;;"HoneySQL form that should be used to get the current `datetime` (or equivalent). Defaults to `:%now`."
(defmethod sql.qp/current-datetime-honeysql-form :ibminformix [_] now) 	

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         metabase.driver.sql-jdbc impls                                         |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod sql-jdbc.conn/connection-details->spec :ibminformix
  [_ {:keys [host port dbname additional-options]
      :or   {host "localhost", port 3386, dbname "" additional-options ""} 
      :as   details}]
  (merge {:classname "com.informix.jdbc.IfxDriver"   ;; must be in classpath
	  :subprotocol "informix-sqli"
          :subname (str "//" host ":" port "/" dbname ":" additional-options)}
         (dissoc details :host :port :dbname :additional-options)))

(defmethod driver/can-connect? :ibminformix [driver details]
  (let [connection (sql-jdbc.conn/connection-details->spec driver (ssh/include-ssh-tunnel! details))]
    (= 1 (first (vals (first (jdbc/query connection ["SELECT 1 FROM SYSTABLES WHERE tabid=1"])))))))

(defmethod sql-jdbc.sync/database-type->base-type :ibminformix [_ database-type]
  ({:bigint       :type/BigInteger    	;; Mappings for Informix types to Metabase types.
    :binary       :type/*             	;; See the list here: https://www.cursor-distribution.de/aktuell.12.10.xC1/documentation/ids_jdbc_bookmap.pdf p.227
    :blob         :type/*
    :boolean      :type/Boolean
    :char         :type/Text
    :clob         :type/Text
    :datalink     :type/*
    :date         :type/Date
    :datetime     :type/DateTime        
    :dbclob       :type/Text
    :decimal      :type/Decimal
    :decfloat     :type/Decimal
    :double       :type/Float
    :float        :type/Float
    :graphic      :type/Text
    :integer      :type/Integer
    :numeric      :type/Decimal
    :real         :type/Float
    :rowid        :type/*
    :smallint     :type/Integer
    :time         :type/Time
    :timestamp    :type/DateTime
    :varchar      :type/Text
    :vargraphic   :type/Text
    :xml          :type/Text
    :lvarchar	  :type/Integer
    :nchar        :type/Text	 	
    :nvarchar     :type/Text	 	
    :int8         :type/BigInteger   	
    :serial8      :type/BigInteger   	
    :serial       :type/Integer 	
	} database-type))

;;; +----------------------------------------------------------------------------------------------------------------+
;; Solution for the Problem, that IBM Informix always throws an Exception for the simple-select-probe-query
(defn simple-select-probe-query
;;   Simple (ie. cheap) SELECT on a given table to test for access and get column metadata. Doesn't return
;;   anything useful (only used to check whether we can execute a SELECT query)
;;     (simple-select-probe-query :postgres \"public\" \"my_table\")
;;     -> [\"SELECT TRUE FROM public.my_table WHERE 1 <> 1 LIMIT 0\"]
   [driver schema table]
   {:pre [(string? table)]}
;;  Using our SQL compiler here to get portable LIMIT (e.g. `SELECT TOP n ...` for SQL Server/Oracle)
;; not explicitly needed for IBM Informix, but is unproblematic, so we leave it here.	
   (let [honeysql {:select [[(sql.qp/->honeysql driver true) :_]]
                   :from   [(sql.qp/->honeysql driver (hx/identifier :table schema table))]
                   :where  [:not= 1 1]}
         honeysql (sql.qp/apply-top-level-clause driver :limit honeysql {:limit 1})]
     (sql.qp/format-honeysql driver honeysql)))
 
(defn- execute-select-probe-query
;;   Execute the simple SELECT query defined above. The main goal here is to check whether we're able to execute a SELECT
;;   query against the Table in question -- we don't care about the results themselves -- so the query and the logic
;;   around executing it should be as simple as possible. We need to highly optimize this logic because it's executed for
;;   every Table on every sync.
   [driver ^Connection conn [sql & params]]
   {:pre [(string? sql)]}
   (with-open [stmt (common/prepare-statement driver conn sql params)]
;;     attempting to execute the SQL statement will throw an Exception if we don't have permissions; otherwise it will
;;     truthy wheter or not it returns a ResultSet, but we can ignore that since we have enough info to proceed at
;;     this point.
     (.execute stmt)))
 
;; withe the IBM Informix Database the simple-select-probe-query always throws an Exception, so:
;; if the query throws an Exception, we continue 
(defmethod i/have-select-privilege? :ibminformix
   [driver conn table-schema table-name]
   (let [sql-args (simple-select-probe-query driver table-schema table-name)]
        (try
        (execute-select-probe-query driver conn sql-args)
         true
        (catch Throwable _
         true))))
;;; +----------------------------------------------------------------------------------------------------------------+

;;(defmethod sql-jdbc.sync/excluded-schemas :ibminformix [_] ;;auskommentiert weil IBM db2 Syntax
;;  #{"SQLJ" 
;;    "SYSCAT" 
;;    "SYSFUN" 
;;    "SYSIBMADM" 
;;    "SYSIBMINTERNAL" 
;;    "SYSIBMTS" 
;;    "SPOOLMAIL"
;;    "SYSPROC" 
;;    "SYSPUBLIC" 
;;    "SYSSTAT"
;;    "SYSTOOLS"})

(defmethod unprepare/unprepare-value [:ibminformix LocalDate]
  [_ t]
(format "date('%s')" (t/format "yyyy-MM-dd" t)))

(defmethod unprepare/unprepare-value [:ibminformix LocalTime]
  [_ t]
(format "time('%s')" (t/format "HH:mm:ss.SSS" t)))

(defmethod unprepare/unprepare-value [:ibminformix OffsetTime]
  [_ t]
(format "time('%s')" (t/format "HH:mm:ss.SSS" t)))

(defmethod unprepare/unprepare-value [:ibminformix LocalDateTime]
  [_ t]
(format "date('%s')" (t/format "yyyy-MM-dd" t)))

(defmethod unprepare/unprepare-value [:ibminformix OffsetDateTime]
  [_ t]
(format "date('%s')" (t/format "yyyy-MM-dd" t)))

(defmethod unprepare/unprepare-value [:ibminformix ZonedDateTime]
  [_ t]
(format "date('%s')" (t/format "yyyy-MM-dd" t)))


