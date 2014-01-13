(ns dojo.client
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:refer-clojure :exclude [chars])
  (:require
   [cljs.core.async :refer [<! >! put! close! timeout]]
   [chord.client :refer [ws-ch]]
   [om.core :as om :include-macros true]
   [om.dom :as dom :include-macros true]))

(enable-console-print!)

(def app-state
  (atom {:messages [{:message "None Yet!"}]}))

(def ws (atom nil))

(def undo-buffer
  (atom [@app-state]))

(defn save-message [e owner]
  (om/set-state! owner :message-to-send (.. e -target -value toUpperCase)))

(defn send-message [ws e owner]
  (put! ws (om/get-state owner :message-to-send))
  (om/set-state! owner :message-to-send "")
  (.preventDefault e))

(defn the-sender [data owner]
  (reify
    om/IInitState
    (init-state [_] {:message-to-send ""})
    om/IRender
    (render [_]
      (dom/form #js {:onSubmit #(send-message @ws % owner)}
                (dom/h3 nil "Send message to server")
                (dom/input
                 #js {:type "text"
                      :ref "text-field"
                      :value (om/get-state owner :message-to-send)
                      :onChange #(save-message % owner)})))))

(defn message [{:keys [message]} owner]
  (om/component
   (dom/li #js {:style {:color "red"}}
           message)))

(defn message-counter [{:keys [messages]} owner]
  (om/component
   (dom/li nil (count messages))))

(defn listen-for-mesages [{:keys [messages]}]
  (go-loop []
           (when-let [msg (<! @ws)]
             (om/update! messages conj msg)
             (recur))))

(defn message-list [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (listen-for-mesages data))
    om/IRender
    (render [_]
      (dom/div nil
               (dom/h3 nil "Messages received from server")
               (dom/button #js {:onClick (fn [e]
                                           (swap! undo-buffer pop)
                                           (reset! app-state (last @undo-buffer)))} "Undo")
               #_(dom/input
                  #js {:type "text"
                       :onChange #(om/update! (:font-color data) (constantly "red"))})
               (om/build message-counter data)
               (dom/ul nil
                       (om/build-all message (data :messages)))))))

(defn install-undo-listener
  [app-state-atom]
  (add-watch app-state-atom :undo
             (fn [key ref old new]
               (when-not (= (last @undo-buffer) new)
                 (swap! undo-buffer conj new)
                 (prn @undo-buffer)))))

(go
 (let [ws-val (<! (ws-ch "ws://localhost:3000/ws")) ; establish web socket connection
       ]
   (reset! ws ws-val)
   (om/root
    app-state
    (fn [app owner]
      (reify
        om/IWillMount
        (will-mount [_]
          (install-undo-listener app-state))
        om/IRender
        (render [_]
          (dom/div nil
                   (om/build the-sender app)
                   (om/build message-list app)))))
    (.getElementById js/document "app"))))
