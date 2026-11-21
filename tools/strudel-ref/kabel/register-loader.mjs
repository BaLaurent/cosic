// Usage: node --import ./register-loader.mjs <script>   (Node >= 20.6). Registers kabel-resolve-hook.mjs.
import { register } from 'node:module';
register('./kabel-resolve-hook.mjs', import.meta.url);
