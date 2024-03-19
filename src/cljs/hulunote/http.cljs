(ns hulunote.http
  (:require [datascript.core :as d]
            [hulunote.import :as import]
            [hulunote.db :as db]
            [cljs-http.client :as http]
            [cljs.core.async :as a :refer [<! go]]
            [hulunote.storage :as storage]
            ;; 临时的 => 需要删除！TODO
            [re-frame.core :as re-frame]
            [hulunote.util :as u]))

(defn info [& args]
  ;;
  (prn args)
  )
(defn prn1 [& args] ;; (prn args)
  ""
  )

(defn version-2-retract [data]
  ;;version 2 的后端 all-nav 依然是全量数据，和 datascript 做全量diff，然后把 all-nav 合并进去
  (let [ ;;获取所有indexeddb 的节点
        ts-map            (dissoc (reduce  #(let [[id ts content opid] %2]
                                              (assoc %1 id [ts opid content]))
                                    {}
                                    (d/q
                                      '[:find ?id ?ts ?content ?opid
                                        :in $
                                        :where
                                        [?e :id ?id]
                                        [?e :updated-at ?ts]
                                        [?e :origin-parid ?opid]
                                        [?e :content ?content]]
                                      (d/db db/dsdb)))
                            "00000000-0000-0000-0000-000000000000")
        all-data          (remove :is-delete
                            (:nav-list data))
        ;; 找到所有外部数据有变更的节点
        filterd-all-data  (filter
                            #(let [[ts opid _ ] (ts-map (:id %))]
                               (and ts
                                 (or (not= ts (:updated-at %))
                                   (not= opid (:parid %)))))
                            all-data)
        filterd-all-data  (set (remove nil?
                                 (map #(second (ts-map (:id %)))
                                   filterd-all-data)))
        need-remove-parid (vec (for [pid filterd-all-data]
                                 [:db.fn/retractAttribute
                                  [:id pid] :parid]))]
    (let [ids
          (clojure.set/difference
            (set (d/q
                   '[:find [?id ...]
                     :in $
                     :where [?e :id ?id]]
                   (d/db db/dsdb)))
            (conj (set (map :id all-data))
              "00000000-0000-0000-0000-000000000000"))]
      (vec
        (concat need-remove-parid
          (vec
            (for [id ids]
              [:db/retractEntity [:id id]])))))))

(defn version-3-retract [data backend-ts]
  ;; version-3 的all-nav 已经是增量数据了，靠时间ts 判断
  ;; all-note 里被删除的note nav 都要从 ds 中删掉
  ;; all-nav 里面被删除的 nav 都要从 ds 中删掉
  ;; 简化了计算
  (let [ ;;获取所有indexeddb 的节点
        direct-deleted-blocks       (set (:nav-list data))
        direct-deleted-blocks       (dissoc (reduce  #(let [[id  opid] %2]
                                                        (assoc %1 id opid))
                                              {}
                                              (d/q
                                                '[:find ?id  ?opid
                                                  :in $ ?removed
                                                  :where
                                                  [?e :id ?id]
                                                  [(?removed ?id)]
                                                  [?e :origin-parid ?opid]]
                                                (d/db db/dsdb)
                                                (set (map :id direct-deleted-blocks))))
                                      "00000000-0000-0000-0000-000000000000")
        _                           (d/transact! db/dsdb (mapv first
                                                           (for [[k v]
                                                                 direct-deleted-blocks]
                                                             [[:db/retract [:id v] :parid [:id k]]
                                                              [:db/retractEntity [:id k]]])))
        all-deleted-notes           (clojure.set/difference
                                      (set (map first (d/q
                                                        '[:find ?id
                                                          :in $
                                                          :where
                                                          [?e :hulunote-notes/id ?id]]
                                                        (d/db db/dsdb))))
                                      (set (map first (:all-note-list
                                                       data))))
        ;;这些 note nav 关系都都要删掉
        all-ds-nav-in-deleted-notes (dissoc (reduce  #(let [[id  opid] %2]
                                                        (assoc %1 id opid))
                                              {}
                                              (d/q
                                                '[:find ?id  ?opid
                                                  :in $ ?removed
                                                  :where
                                                  [?e :hulunote-note ?note-id]
                                                  [(?removed ?note-id)]
                                                  [?e :id ?id]
                                                  [?e :origin-parid ?opid]]
                                                (d/db db/dsdb) all-deleted-notes))
                                      "00000000-0000-0000-0000-000000000000")
        _                           (d/transact! db/dsdb (mapv first
                                                           (for [[k v]
                                                                 all-ds-nav-in-deleted-notes]
                                                             [[:db/retract [:id v] :parid [:id k]]
                                                              [:db/retractEntity [:id k]]])))]
    ))

(comment
  (def all-data [{:properties "", :updated-at "2023-03-22T11:39:16Z", :hulunote-note "89b1d754-959e-4fcd-8c62-eb1978fe238b", :last-account-id 3, :is-display true, :parid "00000000-0000-0000-0000-000000000000", :same-deep-order 0, :content "ROOT", :account-id 3, :id "a526aad4-18a5-493e-8752-121e8abaaaa7", :note-id "89b1d754-959e-4fcd-8c62-eb1978fe238b", :created-at "2023-03-22T11:39:16Z", :is-delete false} {:properties "", :updated-at "2023-03-22T12:44:12Z", :hulunote-note "89b1d754-959e-4fcd-8c62-eb1978fe238b", :last-account-id 3, :is-display true, :parid "a526aad4-18a5-493e-8752-121e8abaaaa7", :same-deep-order 0, :content "testing", :account-id 3, :id "85d7d44d-09f8-4b19-9946-46b14fbc29ea", :note-id "89b1d754-959e-4fcd-8c62-eb1978fe238b", :created-at "2023-03-22T12:44:12Z", :is-delete false} {:properties "", :updated-at "2023-03-22T12:55:13Z", :hulunote-note "89b1d754-959e-4fcd-8c62-eb1978fe238b", :last-account-id 3, :is-display true, :parid "a526aad4-18a5-493e-8752-121e8abaaaa7", :same-deep-order 100, :content "test", :account-id 3, :id "0e8cae19-7d19-4f32-8354-e8b12266b75c", :note-id "89b1d754-959e-4fcd-8c62-eb1978fe238b", :created-at "2023-03-22T12:55:13Z", :is-delete false} {:properties "", :updated-at "2023-03-22T14:01:48Z", :hulunote-note "89b1d754-959e-4fcd-8c62-eb1978fe238b", :last-account-id 3, :is-display true, :parid "a526aad4-18a5-493e-8752-121e8abaaaa7", :same-deep-order 200, :content "hahahahah", :account-id 3, :id "7d51e13b-f776-4fd1-8b86-aa2f038f6388", :note-id "89b1d754-959e-4fcd-8c62-eb1978fe238b", :created-at "2023-03-22T14:01:48Z", :is-delete false} {:properties "", :updated-at "2023-03-22T15:51:17Z", :hulunote-note "89b1d754-959e-4fcd-8c62-eb1978fe238b", :last-account-id 3, :is-display true, :parid "7d51e13b-f776-4fd1-8b86-aa2f038f6388", :same-deep-order 0, :content "快到12点了咯", :account-id 3, :id "dd6e6ff0-cdaa-4725-913e-ccaf09daef0b", :note-id "89b1d754-959e-4fcd-8c62-eb1978fe238b", :created-at "2023-03-22T15:51:17Z", :is-delete false}])

  (let [[attrs relations] (import/separate-attribute-and-relation-data
                            (import/nav-list->db-add all-data))]
    ;; (d/transact! db/dsdb attrs)
    (d/transact! db/dsdb relations)
    )

  )
(defn transact-all-nav
  "导入all-nav到dsdb, 可读取本地文件的数据"
  [data last-ts op-fn]
  (let [ ;;获取所有indexeddb 的节点
        all-data      (remove :is-delete
                        (:nav-list data))
        [attrs relations] (import/separate-attribute-and-relation-data
                            (import/nav-list->db-add all-data))]

    (d/transact! db/dsdb (if last-ts
                           (version-3-retract data last-ts)
                           (version-2-retract  data)))
    (try
      (d/transact! db/dsdb attrs)
      (d/transact! db/dsdb relations)
      (d/transact! db/dsdb [[:db.fn/retractAttribute [:id db/root-id] :parid]])
      (catch :default e
        (info "error " e)))
    (info "完成导入" (js/Date.))
    (when op-fn
      (op-fn))
    ;; (if @is-part-note-load?
    ;;   nil
    ;;   (re-frame/dispatch [:show-process-status false]))
    ;; (prn1 "加载完成....")
    ;;
    (do
      (cond
        (= last-ts 0)
        , (prn1 "0000")
        (> (:backend-ts data) last-ts)
        , (prn1 "1111")
        :else (prn1 "2222")))
    ;;
    (if (empty? (:nav-list data))
      nil             ;; 从缓存中加载的: :nav-list 为空的情况
      (prn "OK load") ;; (a/put! atom-chan/load-chan "load-finished")
      )
    ;;
    (info "获取all nav list,存入dsdb:")
    #_(if-not (u/is-share?)
        (timeout-do
          1
          (do
            ;; 每次加载完所有的nav之后，就dump一次，保存到indexed-db
            ;; 每次修改nav和note都会导致:backend-ts变化
            (async-save-db)
            (async-save-backend-ts (:backend-ts data)))))))

(defn transact-all-note
  "导入note数据到dsdb"
  [all-note-list]
  (d/transact! db/dsdb
    (vec (for [note-id
               (filter #(not ((set (map :hulunote-notes/id all-note-list))
                              %))
                 (->>  (db/select :hulunote-notes/id)
                   (map first)
                   (map :hulunote-notes/id)
                   set))]
           [:db/retractEntity [:hulunote-notes/id note-id]])))
  (d/transact! db/dsdb all-note-list))

(defn execute-cb
  "执行回调的通用函数，支持 Vector, Function 和 Set （多个）。"
  [cb arg]
  (cond
    (vector? cb) (re-frame/dispatch (conj cb arg))
    (fn? cb) (cb arg)
    (set? cb)
    (doseq [cb-1 cb]
      (execute-cb cb-1 arg))))

(defn http-uri [uri]
  (if (u/is-dev?)
    (str "http://127.0.0.1:6689" uri)
    (str "https://hulunote.io" uri)))

(defn go-http-queue
  "go队列里面包含进去！顺序一定对, 不用await！"
  [{:keys [uri params multipart-params event callback get-post]}]
  (go
    (let [token (:token @storage/jwt-auth)
          req   (cond-> {}
                  params (assoc :json-params params)
                  multipart-params (assoc :multipart-params multipart-params)
                  token  (assoc-in [:headers "X-FUNCTOR-API-TOKEN"] token))]
      (info "Req:" req " uri " uri)
      (let [resp (<! ((if (= get-post :get)
                        http/get
                        http/post)
                      (http-uri uri)
                      req)) ;; 下面的data会等上面的resp完成
            data (if (empty? (:data (:body resp)))
                   (:body resp)
                   (:data (:body resp)))
            status (:status resp)
            error-dispatch (fn [err-msg]
                             (do
                               (info "Status:" status " Error:" (:error data))
                               (when-let [err (:err callback)]
                                 (execute-cb err err-msg))))
            error-dispatch-network
            (fn [err-msg]
              (u/alert "The network is unavailable, Please check your network settings"))]
        (cond (contains? data :error)
              , (u/alert (:error data))
              (= status 200)
              (do (info "Response:" data)
                  (when event
                    (if (set? event)
                      (doseq [e event]
                        (re-frame/dispatch (conj e data)))
                      (re-frame/dispatch (conj event data))))
                  ;; 成功后dispatch某内容
                  (when-let [succ (:succ callback)]
                    (execute-cb succ data))
                  (when-let [ok-dispatch (:ok-dispatch callback)]
                    (do
                      (info "执行成功回调 dispatch: " ok-dispatch)
                      (re-frame/dispatch ok-dispatch))))

              :else (error-dispatch-network "操作失败"))))))

(re-frame/reg-fx :http
  (fn http [{:keys [uri params multipart-params event callback get-post] :as fx-data}]
    (go-http-queue fx-data)))

(comment

  (prn @db/dsdb)

  ;; OK 存入navs数据成功！
  (re-frame/dispatch-sync
    [:get-all-nav-payload ["" nil] nil "/test-all-navs.json"])

  ;; OK 存入note数据
  (re-frame/dispatch-sync
    [:get-all-note-list-payload
     {:database ""
      :user-share-path "/test-all-notes.json"}])

  )
(re-frame/reg-event-fx
  :get-all-nav-payload
  (fn get-all-nav-payload
    [{:keys [db]  :as cofx}
     [_ [database last-ts] op-fn user-share-path]]
    {:db db
     :http
     {:uri    (if user-share-path
                user-share-path
                "/ot/get-all-navs-v2")
      :params {:database-name database
               :backend-ts last-ts}
      :callback
      {:succ
       (fn [data]
         (transact-all-nav data last-ts op-fn))}}}))

(re-frame/reg-event-fx
  :get-all-note-list-payload
  (fn get-all-note-list-payload
    [{:keys [db]  :as cofx}
     [_ {:keys [database owner-id user-share-path]}]]
    {:db db
     :http {:uri (if user-share-path
                   user-share-path
                   "/ot/get-all-notes")
            :params (cond->
                        {:database-name database}
                      owner-id
                      (assoc :owner-id owner-id))
            :callback {:succ
                       (fn [{:keys [all-note-list]}]
                         (transact-all-note all-note-list))}}}))


(comment
  (re-frame/dispatch-sync
    [:get-all-nav-payload ["" nil] nil "/test-all-navs.json"])
  (re-frame/dispatch-sync
    [:get-all-note-list-payload
     {:database ""
      :user-share-path "/test-all-notes.json"}]))
(defn database-data-load
  [database-name]
  (do
    (re-frame/dispatch-sync [:get-note-list {:database-name database-name
                                             :page 1
                                             :size 100
                                             :op-fn (fn [{:keys [note-list]}]
                                                      (transact-all-note note-list))}])
    (re-frame/dispatch-sync [:get-all-nav-by-page
                             {:database-name database-name
                              :backend-ts 0
                              :page 1
                              :size 1000
                              :op-fn (fn [{:keys [nav-list] :as data}]
                                       ;; (prn nav-list)
                                       (transact-all-nav data 0 #(prn "OK load") ))}])))

(comment
  (re-frame/dispatch-sync
    [:web-login {:email "hzqi_110@hotmail.com"
                 :password "12345678"
                 :op-fn #(prn %)}])
  ;; ("Response:" {:region nil, :hulunote {:accounts/updated-at "2023-03-22T11:38:50Z", :accounts/show-link false, :accounts/mail "hzqi_110@hotmail.com", :accounts/nickname "JackyWong", :accounts/username "JackyWong", :accounts/need-update-password false, :accounts/id 3, :accounts/is-new-user true, :accounts/created-at "2023-03-22T11:38:50Z", :accounts/cell-number "416b69bc-1a2f-417b-9328-03f557b344be", :accounts/invitation-code "caf5f5a2"}, :token "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiaHVsdW5vdGUiLCJpZCI6MywiZXhwIjoxNjgyOTE2NDA1fQ.5ubQQsOzIW6EFCblLSUSyBVbh0JaCQ-1MQ5vAV-EQFM"})
  )
(re-frame/reg-event-fx
  :web-login
  (fn web-login
    [{:keys [db]  :as cofx}
     [_ {:keys [email password op-fn]}]]
    {:db db
     :http
     {:uri "/login/web-login"
      :params { ;; :username username
               :email email
               :password password
               ;; :binding-code binding-code
               ;; :binding-platform binding-platform
               }
      :callback
      {:succ
       (fn [data]
         (op-fn data))}}}))

(re-frame/reg-event-fx
  :web-signup
  (fn web-signup
    [{:keys [db]  :as cofx}
     [_ {:keys [;; username
                email password ack-number binding-code
                op-fn]}]]
    {:db db
     :http
     {:uri "/login/web-signup"
      :params {:username email;; username
               :email email
               :password password
               :ack-number ack-number
               :binding-code binding-code
               :binding-platform "whatsapp"}
      :callback
      {:succ
       (fn [data]
         (op-fn data))}}}))

(re-frame/reg-event-fx
  :send-ack-msg
  (fn send-ack-msg
    [{:keys [db]  :as cofx}
     [_ {:keys [email op-fn]}]]
    {:db db
     :http
     {:uri "/login/send-ack-msg"
      :params {:email email}
      :callback
      {:succ
       (fn [data]
         (op-fn data))}}}))

(comment
  (re-frame/dispatch-sync [:get-database-list {:op-fn #(cljs.pprint/pprint %)}])
  )
(re-frame/reg-event-fx
  :get-database-list
  (fn get-database-list
    [{:keys [db]  :as cofx}
     [_ {:keys [op-fn]}]]
    {:db db
     :http
     {:uri "/hulunote/get-database-list"
      :params {}
      :callback
      {:succ
       (fn [data]
         (op-fn data))}}}))

;; 分页获取笔记列表 => 性能更好，不会卡死。=>TODO: 先写一个很大的。ds保存一下分页的信息
(comment
  (re-frame/dispatch-sync [:get-note-list {:database-name "JackyWong-5721"
                                           :page 1
                                           :size 100
                                           :op-fn #(cljs.pprint/pprint %)}])
  ;; =>
  {:all-pages 1,
   :note-list
   [{:hulunote-notes/created-at "2023-03-22T11:39:16Z",
     :hulunote-notes/root-nav-id "a526aad4-18a5-493e-8752-121e8abaaaa7",
     :hulunote-notes/pv 0,
     :hulunote-notes/title "2023-03-22",
     :hulunote-notes/is-delete false,
     :hulunote-notes/account-id 3,
     :hulunote-notes/id "89b1d754-959e-4fcd-8c62-eb1978fe238b",
     :hulunote-notes/database-id "dd76de34-28ad-4107-8a54-4789ae06fbdd",
     :hulunote-notes/is-public false,
     :hulunote-notes/updated-at "2023-03-22T11:39:16Z",
     :hulunote-notes/is-shortcut false}
    {:hulunote-notes/created-at "2023-03-23T00:43:55Z",
     :hulunote-notes/root-nav-id "4b1d7a3c-69e3-4153-bf4b-00009935de85",
     :hulunote-notes/pv 0,
     :hulunote-notes/title "2023-03-23",
     :hulunote-notes/is-delete false,
     :hulunote-notes/account-id 3,
     :hulunote-notes/id "a397df63-0417-4c48-9896-56983050b255",
     :hulunote-notes/database-id "dd76de34-28ad-4107-8a54-4789ae06fbdd",
     :hulunote-notes/is-public false,
     :hulunote-notes/updated-at "2023-03-23T00:43:55Z",
     :hulunote-notes/is-shortcut false}]}
  )
(re-frame/reg-event-fx
  :get-note-list
  (fn get-note-list
    [{:keys [db]  :as cofx}
     [_ {:keys [database-name page size op-fn]}]]
    {:db db
     :http
     {:uri "/hulunote/get-note-list"
      :params {:database-name  database-name
               :page  page
               :size size}
      :callback
      {:succ
       (fn [data]
         (op-fn data))}}}))

(comment
  (re-frame/dispatch-sync [:get-all-nav-by-page
                           {:database-name "JackyWong-5721"
                            :backend-ts 0
                            :page 1
                            :size 5
                            :op-fn #(cljs.pprint/pprint %)}])
  ;; =>
  {:nav-list
   [{:properties "",
     :updated-at "2023-03-22T11:39:16Z",
     :hulunote-note "89b1d754-959e-4fcd-8c62-eb1978fe238b",
     :last-account-id 3,
     :is-display true,
     :parid "00000000-0000-0000-0000-000000000000",
     :same-deep-order 0,
     :content "ROOT",
     :account-id 3,
     :id "a526aad4-18a5-493e-8752-121e8abaaaa7",
     :note-id "89b1d754-959e-4fcd-8c62-eb1978fe238b",
     :created-at "2023-03-22T11:39:16Z",
     :is-delete false}
    {:properties "",
     :updated-at "2023-03-22T12:44:12Z",
     :hulunote-note "89b1d754-959e-4fcd-8c62-eb1978fe238b",
     :last-account-id 3,
     :is-display true,
     :parid "a526aad4-18a5-493e-8752-121e8abaaaa7",
     :same-deep-order 0,
     :content "testing",
     :account-id 3,
     :id "85d7d44d-09f8-4b19-9946-46b14fbc29ea",
     :note-id "89b1d754-959e-4fcd-8c62-eb1978fe238b",
     :created-at "2023-03-22T12:44:12Z",
     :is-delete false}
    {:properties "",
     :updated-at "2023-03-22T12:55:13Z",
     :hulunote-note "89b1d754-959e-4fcd-8c62-eb1978fe238b",
     :last-account-id 3,
     :is-display true,
     :parid "a526aad4-18a5-493e-8752-121e8abaaaa7",
     :same-deep-order 100,
     :content "test",
     :account-id 3,
     :id "0e8cae19-7d19-4f32-8354-e8b12266b75c",
     :note-id "89b1d754-959e-4fcd-8c62-eb1978fe238b",
     :created-at "2023-03-22T12:55:13Z",
     :is-delete false}
    {:properties "",
     :updated-at "2023-03-22T14:01:48Z",
     :hulunote-note "89b1d754-959e-4fcd-8c62-eb1978fe238b",
     :last-account-id 3,
     :is-display true,
     :parid "a526aad4-18a5-493e-8752-121e8abaaaa7",
     :same-deep-order 200,
     :content "hahahahah",
     :account-id 3,
     :id "7d51e13b-f776-4fd1-8b86-aa2f038f6388",
     :note-id "89b1d754-959e-4fcd-8c62-eb1978fe238b",
     :created-at "2023-03-22T14:01:48Z",
     :is-delete false}
    {:properties "",
     :updated-at "2023-03-22T15:51:17Z",
     :hulunote-note "89b1d754-959e-4fcd-8c62-eb1978fe238b",
     :last-account-id 3,
     :is-display true,
     :parid "7d51e13b-f776-4fd1-8b86-aa2f038f6388",
     :same-deep-order 0,
     :content "快到12点了咯",
     :account-id 3,
     :id "dd6e6ff0-cdaa-4725-913e-ccaf09daef0b",
     :note-id "89b1d754-959e-4fcd-8c62-eb1978fe238b",
     :created-at "2023-03-22T15:51:17Z",
     :is-delete false}],
   :all-pages 2,                        ;;=> 继续递归下去，取完所有的页面为止
   :backend-ts 1682410341223}           ;;
  )
;; 分页获取笔记库所有笔记节点 => 即使是backend-ts不为零也可获取分页更新
(re-frame/reg-event-fx
  :get-all-nav-by-page
  (fn get-all-nav-by-page
    [{:keys [db]  :as cofx}
     [_ {:keys [database-name backend-ts page size op-fn]}]]
    {:db db
     :http
     {:uri "/hulunote/get-all-nav-by-page"
      :params {:database-name  database-name
               :backend-ts backend-ts
               :page  page
               :size size}
      :callback
      {:succ
       (fn [data]
         (op-fn data))}}}))
