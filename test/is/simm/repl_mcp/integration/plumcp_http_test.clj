(ns is.simm.repl-mcp.integration.plumcp-http-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.server :as server]
            [plumcp.core.api.mcp-client :as mcp-client]
            [plumcp.core.client.http-client-transport :as http-transport]
            [plumcp.core.support.http-client :as http-client]))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(deftest streamable-http-roundtrip-test
  (testing "PlumCP streamable HTTP transport at /mcp"
    (let [port (free-port)
          test-tool {:name "echo"
                     :description "Echo"
                     :inputSchema {:type "object"
                                   :properties {:message {:type "string"}}
                                   :required ["message"]}
                     :tool-fn (fn [_ args]
                                {:content [{:type "text"
                                            :text (str "Echo " (:message args))}]})}
          instance (server/create-mcp-server-instance!
                    {:tools [test-tool]
                     :server-info {:name "http-test" :version "1.0.0"}})
          running-server (server/run-http-instance! instance port)]
      (try
        (Thread/sleep 500)
        (let [http-client (http-client/make-http-client
                           (str "http://127.0.0.1:" port "/mcp"))
              transport (http-transport/make-streamable-http-transport
                         http-client
                         :start-get-stream? false)
              client (mcp-client/make-mcp-client
                      {:info {:name "test-client" :version "1.0.0"}
                       :client-transport transport
                       :print-banner? false
                       :run-list-notifier? false})]
          (try
            (is (= "http-test"
                   (get-in (mcp-client/initialize-and-notify! client)
                           [:serverInfo :name])))
            (is (= "echo" (:name (first (mcp-client/list-tools client)))))
            (is (= "Echo hi"
                   (get-in (mcp-client/call-tool client "echo" {:message "hi"})
                           [:content 0 :text])))
            (finally
              (mcp-client/disconnect! client))))
        (finally
          (server/stop-running-server! running-server))))))
