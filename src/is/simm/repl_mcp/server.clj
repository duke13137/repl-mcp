(ns is.simm.repl-mcp.server
  "Multi-instance MCP server API backed by PlumCP."
  (:require
   [is.simm.repl-mcp.tools :as tools]
   [nrepl.core :as nrepl]
   [plumcp.core.api.capability :as capability]
   [plumcp.core.api.entity-gen :as entity-gen]
   [plumcp.core.api.mcp-server :as mcp-server]
   [plumcp.core.deps.runtime :as runtime]
   [plumcp.core.deps.runtime-support :as runtime-support]
   [plumcp.core.server.server-support :as server-support]
   [taoensso.telemere :as log]
   [clojure.spec.alpha :as s]))

(s/def ::instance-config map?)

(defonce nrepl-clients (atom {}))

(defn create-or-get-nrepl-client
  "Create or reuse nREPL client for given configuration"
  [{:keys [port ip] :or {port 47888 ip "127.0.0.1"}}]
  (let [client-key [port ip]]
    (if-let [existing-client (get @nrepl-clients client-key)]
      (do
        (log/log! {:level :info :msg "Reusing existing nREPL client"
                   :data {:port port :ip ip}})
        existing-client)
      (try
        (log/log! {:level :info :msg "Creating new nREPL client"
                   :data {:port port :ip ip}})
        (let [transport (nrepl/connect :port port :host ip)
              client (nrepl/client transport Long/MAX_VALUE)]
          (swap! nrepl-clients assoc client-key client)
          (log/log! {:level :info :msg "nREPL client created successfully"
                     :data {:port port :ip ip}})
          client)
        (catch Exception e
          (log/log! {:level :error :msg "Failed to create nREPL client"
                     :data {:port port :ip ip :error (.getMessage e)}})
          (throw e))))))

(defn- tool-handler
  [nrepl-client tool]
  (fn [args]
    ((:tool-fn tool) {:nrepl-client nrepl-client} args)))

(defn- ->plumcp-tool
  [nrepl-client tool]
  (capability/make-tool-item
   (:name tool)
   (:inputSchema tool)
   (tool-handler nrepl-client tool)
   :description (:description tool)))

(defn- runtime-context
  [instance]
  (runtime/upsert-runtime {} (:runtime instance)))

(declare notify-tool-list-changed!)

(defn create-mcp-server-instance!
  "Create an MCP server instance backed by PlumCP."
  [config]
  (log/log! {:level :info :msg "Creating MCP server instance" :data {:config config}})
  (try
    (let [nrepl-client (or (:nrepl-client config)
                           (when-let [nrepl-config (:nrepl-config config)]
                             (create-or-get-nrepl-client nrepl-config)))
          tool-defs (vec (or (:tools config) (tools/get-tool-definitions)))
          tools-atom (atom tool-defs)
          tool-items (atom (mapv (partial ->plumcp-tool nrepl-client) tool-defs))
          server-info (or (:server-info config)
                          {:name "repl-mcp" :version "1.0.0"})
          server-options (server-support/make-server-options
                          {:info server-info
                           :primitives {:tools tool-items
                                        :prompts (or (:prompts config) [])
                                        :resources (or (:resources config) [])}})
          instance {:context {:runtime (:runtime server-options)
                              :jsonrpc-handler (:jsonrpc-handler server-options)
                              :nrepl-client nrepl-client}
                    :runtime (:runtime server-options)
                    :jsonrpc-handler (:jsonrpc-handler server-options)
                    :server-options server-options
                    :tools tools-atom
                    :tool-items tool-items
                    :session tool-items
                    :config config
                    :nrepl-client nrepl-client
                    :created-at (java.time.Instant/now)}]
      (log/log! {:level :info :msg "MCP server instance created successfully"
                 :data {:has-nrepl-client (some? nrepl-client)
                        :tool-count (count tool-defs)}})
      instance)
    (catch Exception e
      (log/log! {:level :error :msg "Failed to create MCP server instance"
                 :data {:error (.getMessage e)}})
      (throw e))))

(defn add-tool!
  "Add a tool to the MCP server instance."
  [instance tool]
  (try
    (log/log! {:level :info :msg "Adding tool to instance"
               :data {:tool-name (:name tool)}})
    (swap! (:tools instance) conj tool)
    (swap! (:tool-items instance) conj (->plumcp-tool (:nrepl-client instance) tool))
    (notify-tool-list-changed! instance)
    (log/log! {:level :info :msg "Tool added successfully"
               :data {:tool-name (:name tool)}})
    :added
    (catch Exception e
      (log/log! {:level :error :msg "Failed to add tool"
                 :data {:tool-name (:name tool) :error (.getMessage e)}})
      (throw e))))

(defn remove-tool!
  "Remove a tool from the MCP server instance."
  [instance tool-name]
  (try
    (log/log! {:level :info :msg "Removing tool from instance"
               :data {:tool-name tool-name}})
    (swap! (:tools instance) #(vec (remove (comp #{tool-name} :name) %)))
    (swap! (:tool-items instance) #(vec (remove (comp #{tool-name} :name) %)))
    (notify-tool-list-changed! instance)
    (log/log! {:level :info :msg "Tool removed successfully"
               :data {:tool-name tool-name}})
    :removed
    (catch Exception e
      (log/log! {:level :error :msg "Failed to remove tool"
                 :data {:tool-name tool-name :error (.getMessage e)}})
      (throw e))))

(defn notify-tool-list-changed!
  "Notify initialized clients that the tool list has changed."
  [instance]
  (try
    (log/log! {:level :info :msg "Notifying tool list changed"})
    (runtime-support/notify-initialized-clients
     (runtime-context instance)
     (entity-gen/make-tool-list-changed-notification))
    (log/log! {:level :info :msg "Tool list change notification sent"})
    :notified
    (catch Exception e
      (log/log! {:level :error :msg "Failed to notify tool list changed"
                 :data {:error (.getMessage e)}})
      (throw e))))

(defn get-tools
  "Get all tools from an instance"
  [instance]
  @(:tools instance))

(defn get-tool
  "Get a specific tool by name from an instance"
  [instance tool-name]
  (first (filter #(= tool-name (:name %)) @(:tools instance))))

(defn list-tool-names
  "List all tool names in an instance"
  [instance]
  (mapv :name @(:tools instance)))

(defn instance-info
  "Get information about an instance"
  [instance]
  (select-keys instance [:config :created-at :nrepl-client]))

(defn run-stdio-instance!
  [instance]
  (mcp-server/run-mcp-server
   (assoc (:server-options instance)
          :transport :stdio
          :print-banner? false)))

(defn run-http-instance!
  [instance http-port]
  (mcp-server/run-mcp-server
   (assoc (:server-options instance)
          :transport :http
          :port http-port
          :uri-set #{"/mcp" "/mcp/"}
          :print-banner? false)))

(defn stop-running-server!
  [running-server]
  (mcp-server/stop-server running-server))

(defn create-stdio-instance!
  "Create an instance configured for STDIO transport"
  [& {:keys [tools nrepl-config]
      :or {tools (tools/get-tool-definitions)
           nrepl-config {:port 47888}}}]
  (create-mcp-server-instance!
   {:tools tools
    :nrepl-config nrepl-config
    :server-info {:name "repl-mcp-stdio" :version "1.0.0"}}))

(defn create-sse-instance!
  "Create an instance configured for SSE transport"
  [& {:keys [tools nrepl-config http-port]
      :or {tools (tools/get-tool-definitions)
           nrepl-config {:port 47888}
           http-port 18080}}]
  (create-mcp-server-instance!
   {:tools tools
    :nrepl-config nrepl-config
    :transport-config {:type :sse :port http-port :ip "127.0.0.1"}
    :server-info {:name "repl-mcp-sse" :version "1.0.0"}}))

(defn close-nrepl-client!
  "Close a specific nREPL client"
  [{:keys [port ip] :or {port 47888 ip "127.0.0.1"}}]
  (let [client-key [port ip]]
    (when-let [client (get @nrepl-clients client-key)]
      (try
        (log/log! {:level :info :msg "Closing nREPL client"
                   :data {:port port :ip ip}})
        (.close client)
        (swap! nrepl-clients dissoc client-key)
        (log/log! {:level :info :msg "nREPL client closed"
                   :data {:port port :ip ip}})
        (catch Exception e
          (log/log! {:level :warn :msg "Error closing nREPL client"
                     :data {:port port :ip ip :error (.getMessage e)}}))))))

(defn close-all-nrepl-clients!
  "Close all nREPL clients"
  []
  (doseq [[client-key client] @nrepl-clients]
    (try
      (.close client)
      (catch Exception e
        (log/log! {:level :warn :msg "Error closing nREPL client during shutdown"
                   :data {:client-key client-key :error (.getMessage e)}}))))
  (reset! nrepl-clients {}))
