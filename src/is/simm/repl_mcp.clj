(ns is.simm.repl-mcp
  "repl-mcp server using PlumCP transports."
  (:require
   [is.simm.repl-mcp.server :as server]
   [is.simm.repl-mcp.tools :as tools]
   [is.simm.repl-mcp.logging :as logging]
   [is.simm.repl-mcp.transport.sse :as sse]
   [nrepl.server :as nrepl-server]
   [taoensso.telemere :as log]
   [clojure.tools.cli :as cli]
   [clojure.string :as str]
   [jsonista.core :as j]
   [plumcp.core.schema.json-rpc :as json-rpc]
   [plumcp.core.schema.schema-defs :as schema-defs]
   [cider.nrepl :refer [cider-nrepl-handler]]
   [refactor-nrepl.middleware :refer [wrap-refactor]])
  (:gen-class)
  (:import (clojure.lang LineNumberingPushbackReader)
           (com.fasterxml.jackson.annotation JsonInclude$Include)))

(def cli-options
  [["-p" "--nrepl-port PORT" "nREPL server port"
    :default 47888
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]

   ["-h" "--http-port PORT" "HTTP server port for HTTP or legacy SSE transport"
    :default 18080
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]

   ["-t" "--transport TRANSPORT" "Transport type (stdio, http, or sse)"
    :default :stdio
    :parse-fn keyword
    :validate [#{:stdio :http :sse} "Must be 'stdio', 'http', or 'sse'"]]

   ["-v" "--verbose" "Enable verbose logging"]

   ["-?" "--help" "Show help"]])

(defn usage [options-summary]
  (->> ["repl-mcp server using PlumCP"
        ""
        "Usage: repl-mcp [options]"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  repl-mcp                         # Start with STDIO transport"
        "  repl-mcp -t http -h 18080        # Start streamable HTTP at /mcp"
        "  repl-mcp -t sse -h 18080         # Start legacy SSE at /sse"
        ""]
       (str/join "\n")))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join "\n" errors)))

(defn validate-args
  "Validate command line arguments."
  [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}

      errors
      {:exit-message (error-msg errors)}

      :else
      {:options options})))

(defonce server-state (atom nil))

(defn json-rpc-object-mapper
  "Create a JSON mapper for JSON-RPC messages.

  JSON-RPC parse-error responses must include an explicit `\"id\": null`;
  force Jackson to keep nil map values so that required null is not omitted."
  [opts]
  (doto (j/object-mapper opts)
    (.setSerializationInclusion JsonInclude$Include/ALWAYS)))

(def parse-error-response
  (assoc (json-rpc/jsonrpc-failure schema-defs/error-code-parse-error "Parse error")
         :id nil))

(defn listen-messages
  "Compatibility JSON line reader used by parse-error tests. STDIO runtime uses PlumCP native transport."
  [context ^LineNumberingPushbackReader reader]
  (let [{:keys [send-message]} context
        json-mapper (json-rpc-object-mapper {:decode-key-fn keyword})]
    (loop []
      (when-some [line (.readLine reader)]
        (if (try
              (j/read-value line json-mapper)
              true
              (catch Exception e
                (log/log! {:level :error :msg "JSON parse error"
                           :data {:line line :error (.getMessage e)}})
                (send-message parse-error-response)
                false))
          (throw (ex-info "listen-messages compatibility reader only handles parse-error tests"
                          {:line line}))
          (recur))))))

(defn start-nrepl-server!
  "Start nREPL server with cider and refactor middleware"
  [port]
  (try
    (log/log! {:level :info :msg "Starting nREPL server" :data {:port port}})
    (let [middleware-stack (-> cider-nrepl-handler
                               (wrap-refactor))
          server (nrepl-server/start-server
                  :port port
                  :handler middleware-stack
                  :bind "127.0.0.1")]
      (log/log! {:level :info :msg "nREPL server started" :data {:port port}})
      server)
    (catch Exception e
      (if (re-find #"Address already in use" (.getMessage e))
        (do
          (log/log! {:level :info :msg "nREPL server already running" :data {:port port}})
          nil)
        (throw e)))))

(defn instance-config [config]
  {:tools (tools/get-tool-definitions)
   :nrepl-config {:port (:nrepl-port config)
                  :ip "127.0.0.1"}
   :server-info {:name "repl-mcp" :version "1.0.0"}})

(defn start-mcp-server!
  "Start the repl-mcp server."
  [config]
  (log/log! {:level :info :msg "Starting repl-mcp server" :data config})
  (let [nrepl-server (start-nrepl-server! (:nrepl-port config))]
    (Thread/sleep 1000)
    (let [instance-config (instance-config config)
          instance (server/create-mcp-server-instance! instance-config)
          running-server (when (= (:transport config) :http)
                           (server/run-http-instance! instance (:http-port config)))
          http-server (when (= (:transport config) :sse)
                        (log/log! {:level :info :msg "Initializing legacy SSE HTTP server"
                                   :data {:port (:http-port config)}})
                        (sse/start-http-server!
                         #(server/create-mcp-server-instance! instance-config)
                         (:http-port config)))]
      (when running-server
        (log/log! {:level :info :msg "Streamable HTTP MCP server started"
                   :data {:port (:http-port config)
                          :url (str "http://127.0.0.1:" (:http-port config) "/mcp")}}))
      (when http-server
        (log/log! {:level :info :msg "Legacy HTTP+SSE server started"
                   :data {:port (:http-port config)
                          :url (str "http://127.0.0.1:" (:http-port config) "/sse")}}))
      (reset! server-state {:nrepl-server nrepl-server
                            :instance instance
                            :running-server running-server
                            :http-server http-server
                            :config config})
      (log/log! {:level :info :msg "repl-mcp server started successfully"
                 :data {:transport (:transport config)
                        :nrepl-port (:nrepl-port config)
                        :tool-count (count (tools/get-tool-definitions))
                        :http-port (when (or running-server http-server)
                                     (:http-port config))}})
      {:nrepl-server nrepl-server
       :instance instance
       :running-server running-server
       :http-server http-server})))

(defn stop-mcp-server!
  "Stop the repl-mcp server"
  []
  (when-let [state @server-state]
    (log/log! {:level :info :msg "Stopping repl-mcp server"})
    (when-let [running-server (:running-server state)]
      (try
        (server/stop-running-server! running-server)
        (log/log! {:level :info :msg "PlumCP server stopped"})
        (catch Exception e
          (log/log! {:level :warn :msg "Error stopping PlumCP server"
                     :data {:error (.getMessage e)}}))))
    (when-let [http-server (:http-server state)]
      (try
        (sse/stop-http-server! http-server)
        (log/log! {:level :info :msg "HTTP server stopped"})
        (catch Exception e
          (log/log! {:level :warn :msg "Error stopping HTTP server"
                     :data {:error (.getMessage e)}}))))
    (when-let [nrepl-server (:nrepl-server state)]
      (try
        (nrepl-server/stop-server nrepl-server)
        (log/log! {:level :info :msg "nREPL server stopped"})
        (catch Exception e
          (log/log! {:level :warn :msg "Error stopping nREPL server"
                     :data {:error (.getMessage e)}}))))
    (server/close-all-nrepl-clients!)
    (reset! server-state nil)
    (log/log! {:level :info :msg "repl-mcp server stopped"})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    (logging/setup-file-logging! (= (:transport options) :stdio))
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (do
        (when (:verbose options)
          (log/set-min-level! :debug))
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. #(stop-mcp-server!)))
        (try
          (let [config {:nrepl-port (:nrepl-port options)
                        :http-port (:http-port options)
                        :transport (:transport options)}]
            (start-mcp-server! config)
            (case (:transport options)
              :stdio
              (do
                (log/log! {:level :info :msg "STDIO MCP server ready for connections"})
                (server/run-stdio-instance! (:instance @server-state)))

              :http
              (do
                (log/log! {:level :info :msg "Streamable HTTP MCP server ready"
                           :data {:http-port (:http-port options)
                                  :url (str "http://127.0.0.1:" (:http-port options) "/mcp")}})
                @(promise))

              :sse
              (do
                (log/log! {:level :info :msg "Legacy SSE MCP server ready"
                           :data {:http-port (:http-port options)}})
                @(promise))))
          (catch Exception e
            (log/log! {:level :error :msg "Failed to start server"
                       :data {:error (.getMessage e)}})
            (exit 1 (str "Error: " (.getMessage e)))))))))

(comment
  (start-mcp-server! {:nrepl-port 47888
                      :transport :stdio})
  (stop-mcp-server!))
