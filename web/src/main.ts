import { createApp } from "vue";

import App from "./App.vue";
import "./style.css";
import { connectBackend } from "./store";

createApp(App).mount("#app");
connectBackend();
