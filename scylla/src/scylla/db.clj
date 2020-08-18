(ns scylla.db
  "Database setup and teardown."
  (:require [clojure [pprint :refer :all]
             [string :as str]]
            [clojure.java.io :as io]
            [clojure.java.jmx :as jmx]
            [clojure.set :as set]
            [clojure.tools.logging :refer [info]]
            [jepsen
             [db        :as db]
             [util      :as util :refer [meh timeout]]
             [control   :as c :refer [| lit]]
             [client    :as client]
             [generator :as gen]
             [tests     :as tests]]
            [jepsen.control [net :as net]]
            [jepsen.os.debian :as debian]
            [scylla [client :as sc]
                    [generator :as sgen]])
  (:import (clojure.lang ExceptionInfo)
           (com.datastax.driver.core Session)
           (com.datastax.driver.core Cluster)
           (com.datastax.driver.core Metadata)
           (com.datastax.driver.core Host)
           (com.datastax.driver.core.policies RetryPolicy
                                              RetryPolicy$RetryDecision)
           (java.net InetAddress)))

(defn compaction-strategy
  "Returns the compaction strategy to use"
  []
  (or (System/getenv "JEPSEN_COMPACTION_STRATEGY")
      "SizeTieredCompactionStrategy"))

(defn compressed-commitlog?
  "Returns whether to use commitlog compression"
  []
  (= (some-> (System/getenv "JEPSEN_COMMITLOG_COMPRESSION") (str/lower-case))
     "false"))

(defn coordinator-batchlog-disabled?
  "Returns whether to disable the coordinator batchlog for MV"
  []
  (boolean (System/getenv "JEPSEN_DISABLE_COORDINATOR_BATCHLOG")))

(defn phi-level
  "Returns the value to use for phi in the failure detector"
  []
  (or (System/getenv "JEPSEN_PHI_VALUE")
      8))

(defn disable-hints?
  "Returns true if Jepsen tests should run without hints"
  []
  (not (System/getenv "JEPSEN_DISABLE_HINTS")))

(defn wait-for-recovery
  "Waits for the driver to report all nodes are up"
  [timeout-secs conn]
  (timeout (* 1000 timeout-secs)
           (throw (RuntimeException.
                   (str "Driver didn't report all nodes were up in "
                        timeout-secs "s - failing")))
           (while (->> conn
                       .getCluster
                       .getMetadata
                       .getAllHosts
                       (map #(.isUp %))
                       and
                       not)
             (Thread/sleep 500))))

(defn dns-resolve
  "Gets the address of a hostname"
  [hostname]
  (.getHostAddress (InetAddress/getByName (name hostname))))

(defn live-nodes
  "Get the list of live nodes from a random node in the cluster"
  [test]
  (set (some (fn [node]
               (try (jmx/with-connection {:host (name node) :port 7199}
                      (jmx/read "org.apache.cassandra.db:type=StorageService"
                                :LiveNodes))
                    (catch Exception _
                      (info "Couldn't get status from node" node))))
             (-> test :nodes set (set/difference @(:bootstrap test))
                 (#(map (comp dns-resolve name) %)) set (set/difference @(:decommission test))
                 shuffle))))

(defn joining-nodes
  "Get the list of joining nodes from a random node in the cluster"
  [test]
  (set (mapcat (fn [node]
                 (try (jmx/with-connection {:host (name node) :port 7199}
                        (jmx/read "org.apache.cassandra.db:type=StorageService"
                                  :JoiningNodes))
                      (catch Exception _
                        (info "Couldn't get status from node" node))))
               (-> test :nodes set (set/difference @(:bootstrap test))
                   (#(map (comp dns-resolve name) %)) set (set/difference @(:decommission test))
                   shuffle))))

(defn nodetool
  "Run a nodetool command"
  [node & args]
  (c/on node (apply c/exec (lit "nodetool") args)))

(defn install!
  "Installs ScyllaDB on the given node."
  [node version]
  (c/su
    (c/cd "/tmp"
          (info "installing ScyllaDB")
          (debian/add-repo!
            "scylla"
            (str "deb  [arch=amd64] http://downloads.scylladb.com/downloads/"
                 "scylla/deb/debian/scylladb-" version " buster non-free")
            "hkp://keyserver.ubuntu.com:80"
            "5e08fbd8b5d6ec9c")
          ; Scylla wants to install SNTP/NTP, which is going to break in
          ; containers--we skip the install here.
          (debian/install [:scylla :scylla-jmx :scylla-tools :ntp-])

          (info "configuring scylla logging")
          (c/exec :mkdir :-p (lit "/var/log/scylla"))
          (c/exec :install :-o :root :-g :adm :-m :0640 "/dev/null"
                  "/var/log/scylla/scylla.log")
          (c/exec :echo
                  ":syslogtag, startswith, \"scylla\" /var/log/scylla/scylla.log\n& ~" :> "/etc/rsyslog.d/10-scylla.conf")
          (c/exec :service :rsyslog :restart)

          ; We don't presently use this, but it might come in handy if we have
          ; to test binaries later.
          (info "copy scylla start script to node")
          (c/su
            (c/exec :echo (slurp (io/resource "start-scylla.sh"))
                    :> "/start-scylla.sh")
            (c/exec :chmod :+x "/start-scylla.sh")))))

(defn seeds
  "Returns a comma-separated string of seed nodes to join to."
  [test]
  (->> (:nodes test)
       (map dns-resolve)
       (str/join ",")))

; TODO: startup indicated by log line "started listening for CQL clients"
; Also checks for gossip messages
; https://github.com/scylladb/scylla-ccm/blob/next/ccmlib/scylla_cluster.py#L76

(defn configure!
  "Uploads configuration files to the given node."
  [node test]
  (info node "configuring ScyllaDB")
  (c/su
    (c/exec :echo (slurp (io/resource "default/scylla-server"))
            :> "/etc/default/scylla-server")
   (doseq [rep (into ["\"s/.*cluster_name: .*/cluster_name: 'jepsen'/g\""
                      "\"s/row_cache_size_in_mb: .*/row_cache_size_in_mb: 20/g\""
                      (str "\"s/seeds: .*/seeds: '" (seeds test) "'/g\"")
                      (str "\"s/listen_address: .*/listen_address: " (dns-resolve node)
                           "/g\"")
                      (str "\"s/rpc_address: .*/rpc_address: " (dns-resolve node) "/g\"")
                      (str "\"s/broadcast_rpc_address: .*/broadcast_rpc_address: "
                           (net/local-ip) "/g\"")
                      "\"s/internode_compression: .*/internode_compression: none/g\""
                      (str "\"s/hinted_handoff_enabled:.*/hinted_handoff_enabled: "
                           (disable-hints?) "/g\"")
                      "\"s/commitlog_sync: .*/commitlog_sync: batch/g\""
                      (str "\"s/# commitlog_sync_batch_window_in_ms: .*/"
                           "commitlog_sync_batch_window_in_ms: 1/g\"" )
                      "\"s/commitlog_sync_period_in_ms: .*/#/g\""
                      (str "\"s/# phi_convict_threshold: .*/phi_convict_threshold: " (phi-level)
                           "/g\"")
                      "\"s/# developer_mode: false/developer_mode: true/g\""
                      "\"/auto_bootstrap: .*/d\""]
                     (when (compressed-commitlog?)
                       ["\"s/#commitlog_compression.*/commitlog_compression:/g\""
                        (str "\"s/#   - class_name: LZ4Compressor/"
                             "    - class_name: LZ4Compressor/g\"")]))]
     (c/exec :sed :-i (lit rep) "/etc/scylla/scylla.yaml"))
   (c/exec :echo (str "auto_bootstrap: "  true)
           :>> "/etc/scylla/scylla.yaml")))

(defn start!
  "Starts ScyllaDB"
  [node _]
  (info node "starting ScyllaDB")
  (c/su
    (c/exec :service :scylla-server :start)
    (info node "started ScyllaDB")))

(defn guarded-start!
  "Guarded start that only starts nodes that have joined the cluster already
  through initial DB lifecycle or a bootstrap. It will not start decommissioned
  nodes."
  [node test]
  (let [bootstrap     (:bootstrap test)
        decommission  (:decommission test)]
    (when-not (or (contains? @bootstrap node)
                  (->> node name dns-resolve (get decommission)))
      (start! node test))))

(defn stop!
  "Stops ScyllaDB"
  [node]
  (info node "stopping ScyllaDB")
  (c/su
   (meh (c/exec :killall :scylla-jmx))
   (while (str/includes? (c/exec :ps :-ef) "scylla-jmx")
     (Thread/sleep 100))
   (meh (c/exec :killall :scylla))
   (while (str/includes? (c/exec :ps :-ef) "scylla")
     (Thread/sleep 100)))
  (info node "has stopped ScyllaDB"))

(defn wipe!
  "Shuts down Scylla and wipes data."
  [node]
  (stop! node)
  (info node "deleting data files")
  (c/su
    ; TODO: wipe log files?
    (meh (c/exec :rm :-rf (lit "/var/lib/scylla/data/*")))
    (meh (c/exec :rm :-rf "/var/log/scylla/scylla.log"))))

(defn db
  "New ScyllaDB run"
  [version]
  (reify db/DB
    (setup! [_ test node]
      (doto node
        (install! version)
        (configure! test)
        (guarded-start! test))
      (sc/close! (sc/await-open node))
      (info "Scylla startup complete"))

    (teardown! [_ test node]
      (wipe! node))

    db/LogFiles
    (log-files [db test node]
      ["/var/log/scylla/scylla.log"])))