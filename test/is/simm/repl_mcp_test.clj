(ns is.simm.repl-mcp-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp :as repl-mcp]
            [jsonista.core :as j]
            [plumcp.core.schema.schema-defs :as schema-defs])
  (:import (clojure.lang LineNumberingPushbackReader)
           (java.io StringReader)))

(deftest listen-messages-parse-error-preserves-null-id-test
  (testing "JSON-RPC parse errors include the required explicit id:null"
    (let [encoded-responses (atom [])
          json-mapper (repl-mcp/json-rpc-object-mapper {:decode-key-fn keyword
                                                        :encode-key-fn name})
          context {:send-message (fn [message]
                                   (swap! encoded-responses conj
                                          (j/write-value-as-string message json-mapper)))}
          reader (LineNumberingPushbackReader. (StringReader. "{not-json\n"))]
      (repl-mcp/listen-messages context reader)
      (is (= 1 (count @encoded-responses)))
      (let [response (j/read-value (first @encoded-responses) json-mapper)]
        (is (= {:code schema-defs/error-code-parse-error :message "Parse error" :data {}} (:error response)))
        (is (contains? response :id))
        (is (nil? (:id response)))))))
