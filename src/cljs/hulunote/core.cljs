(ns hulunote.core
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [datascript.core :as d]
   [hulunote.db :as db]
   [rum.core :as rum]
   [datascript.transit :as dt]
   [hulunote.dom :as dom]
   [hulunote.util :as u]
   [hulunote.http :as http]
   [hulunote.router :as router]
   [goog.events :as events]
   [goog.history.EventType :as HistoryEventType]
   [reitit.core :as reitit]
   [hulunote.render :as render]
   [hulunote.database :as database]
   [hulunote.home :as home]
   [hulunote.show :as show]
   [hulunote.graph :as graph]
   [hulunote.diaries :as diaries]
   [hulunote.login :as login]
   [hulunote.components :as comps]
   ;; [hulunote.main :as main]
   ;; [hulunote.price :as price]
   ;; [hulunote.download :as download]
   )
  (:require-macros
   [hulunote.share :refer [profile]])
  (:import goog.History))

(comment
  (all-notes @db/dsdb)
  (d/pull-many
    @db/dsdb
    [:*]
    (map
      :e
      (d/datoms @db/dsdb :aevt :hulunote-notes/title)))

  (for [note (all-notes db)]
    [:<>
     [:div.flex.flex-row
      [:div.mr2 (:hulunote-notes/id note)]
      [:div (:hulunote-notes/title note)]]])
  )
(defn all-notes [db]
  (->> (d/datoms db :aevt :hulunote-notes/title)
    (map :e)
    (d/pull-many db [:*])))

(rum/defc main-page [db]
  [:div "main page"])

(rum/defc price-page [db]
  [:div "price page"])

(rum/defc download-page [db]
  [:div "download page"])

(rum/defc not-found-component []
  [:div "not-found page"])

(rum/defc app < rum/reactive
  [conn]
  (let [db (rum/react conn)
        {:keys [route-name params]} (db/get-route db)]
    [:div
     (case route-name
       :database (database/database-page db)
       ;; 笔记库的列表，详情，图页面
       :home (home/home-page db)
       :show (show/show-page db)
       :graph (graph/graph-page db)
       :diaries (diaries/diaries-page db)
       ;; 首页：登录，主页，价格，下载
       :login (login/login-page db)
       :main (main-page db)
       :price (price-page db)
       :download (download-page db)
       (not-found-component))
     (comps/toast db)]))

(defn hook-browser-navigation! []
  (doto (History.)
    (events/listen
      HistoryEventType/NAVIGATE
      (fn [event]
        (do
          (let [uri (or (not-empty (str/replace (.-token ^js event) #"^.*#" "")) "/")
                match (reitit/match-by-path router/router uri)
                current-page (:name (:data match))
                route-params (:path-params match)
                {:keys [page-id database]} route-params]
            ;;(prn "router: " match "," route-params)
            (when current-page
              (router/navigate current-page route-params))))))
    (.setEnabled true)))

(defn render []
  (rum/mount (app db/dsdb) (js/document.getElementById "app")))

(defn init! []
  (db/init-db)
  ;; http://127.0.0.1:6689/#/app/JackyWong-5721/diaries
  (if (re-find
        #"#/app/(.*)"
        (.-hash js/window.location))
    (let [db
          (js/decodeURIComponent
            (nth
              (clojure.string/split (.-hash js/window.location)
                #"/")
              2))]
      (http/database-data-load db)))
  (hook-browser-navigation!)
  (render))

(defn after-load []
  (prn "reload")
  (render))
