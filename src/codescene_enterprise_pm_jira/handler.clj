(ns codescene-enterprise-pm-jira.handler
  (:gen-class)
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.util.response :refer [response content-type redirect]]
            [ring.util.codec :refer [form-encode]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]
            [hiccup.form :as form]
            [taoensso.timbre :as log]
            [codescene-enterprise-pm-jira.db :as db]
            [codescene-enterprise-pm-jira.storage :as storage]
            [codescene-enterprise-pm-jira.project-config :as project-config]
            [codescene-enterprise-pm-jira.jira :as jira]))

(defn sync-project
  "Tries to sync the JIRA project using the given credentials and the project
  key. Returns nil if successful and a error message string if failed."
  [username password key]
  (if-let [project-config (project-config/read-config-for-project key)]
    (do
      (log/info "Syncing project" key)
      (let [cost-field (get project-config :cost-field "timeoriginalestimate")
            issues (jira/find-issues-with-cost username password key cost-field)]
        (if (seq issues)
          (do
            (storage/replace-project (db/persistent-connection) project-config issues)
            (log/info "Replaced issues in project" key "with" (count issues) "issues."))
          (format "Could not get issues from JIRA for project %s." key))))
    (format "Cannot sync non-configured project %s!" key)))

(defn- status-page [error]
  (-> (html5 (html [:h1 "CodeScene Enterprise JIRA Integration"]
                   (when error
                     [:div.error error])
                   (form/form-to [:post "/sync/force"]
                                 (form/text-field
                                  {:placeholder "Project Key"}
                                  "project-key")
                                 (form/text-field
                                  {:placeholder "JIRA User Name"}
                                  "username")
                                 (form/password-field
                                  {:placeholder "JIRA Password"}
                                  "password")
                                 (form/submit-button "Force Sync"))))
      response
      (content-type "text/html")))

(defn- replace-with-nil
  "Retain all values in 'all' that exists in 'v', replace others with nil.

  (replace-with-nil [1 2 3 4 5 6] [4 5 1])
  ;=> [1 nil nil 4 5 nil]
  "
  [all v]
  (map #(v %1) all))

(defn- issue->response [all-work-types {:keys [key cost work-types]}]
  (let [work-type-flags (map #(if %1 1 0)
                             (replace-with-nil all-work-types work-types))]
    {:id key
     :cost cost
     :types work-type-flags}))

(defn- project->response [{:keys [key cost-unit work-types issues]}]
  (let [work-types-ordered (vec work-types)]
    {:id key
     :costUnit cost-unit
     :workTypes work-types
     :issues (map (partial issue->response work-types-ordered) issues)}))

(defn- get-project [project-id]
  (-> (storage/get-project (db/persistent-connection) project-id)
      project->response))

(defroutes app-routes
  (GET "/" [error]
       (status-page error))

  (GET "/api/1/projects/:project-id" [project-id]
       (response (get-project project-id)))

  (POST "/sync/force" [username password project-key]
        (if-let [error-message (sync-project username password project-key)]
          (content-type
           (redirect (str "/?" (form-encode {:error error-message})) :see-other)
           "text/html")
          (content-type
           (redirect "/" :see-other)
           "text/html")))

  (route/not-found "Not Found"))

(def app
  (-> app-routes
      (wrap-params)
      (wrap-json-response)
      (wrap-defaults api-defaults)))

(defn init []
  (log/info "init called")
  (db/init))

(defn destroy []
  (log/info "destroy called"))
