import {
	NativeModules,
	AppState,
	DeviceEventEmitter,
	Platform
 } from 'react-native';

const { FPStaticServer } = NativeModules;

const PORT = '';
const ROOT = null;
const UPLOAD = null;
const TIMEOUT = 5000;
const LOCALHOST = 'http://127.0.0.1:';

class StaticServer {
	constructor(port, root, opts) {
		switch (arguments.length) {
			case 3:
				this.port = `${port}` || PORT;
				this.root = root || ROOT;
				this.localOnly = (opts && opts.localOnly) || false;
				this.tryAssets = (opts && opts.tryAssets) || false;
				this.keepAlive = (opts && opts.keepAlive) || false;
				this.uploadDir = (opts && opts.uploadDir) || UPLOAD;
				this.timeoutInMillis = (opts && opts.timeoutInMillis) || TIMEOUT;
				break;
			case 2:
				this.port = `${port}`;
				if (typeof(arguments[1]) === 'string') {
					this.root = root;
					this.localOnly = false;
					this.keepAlive = false;
				} else {
					this.root = ROOT;
					this.localOnly = (arguments[1] && arguments[1].localOnly) || false;
					this.keepAlive = (arguments[1] && arguments[1].keepAlive) || false;
				}
				break;
			case 1:
				if (typeof(arguments[0]) === 'number') {
					this.port = `${port}`;
					this.root = ROOT;
					this.localOnly = false;
					this.keepAlive = false;
				} else {
					this.port = PORT;
					this.root = ROOT;
					this.localOnly = (arguments[0] && arguments[0].localOnly) || false;
					this.keepAlive = (arguments[0] && arguments[0].keepAlive) || false;
				}
				break;
			default:
				this.port = PORT;
				this.root = ROOT;
				this.localOnly = false;
				this.keepAlive = false;
		}

		this.started = false;
		this.routes = [];
		this._origin = undefined;
		DeviceEventEmitter.addListener('webServerRNRequest', ({__id, __files, __uri, ...params}) => {
			console.warn('here', __id, __files, __uri);
			// const {__id, __files, __uri, ...params} = e.nativeEvent;
			for (let route of this.routes) {
				if (route.pattern.exec(__uri)) {
					route.handle({files: __files, params}, (result) => {
						FPStaticServer.response(__id, JSON.stringify(result));
					});
					return;
				}
			}
			console.warn(`no handler for ${__uri}`);
		});
	}

	route(pattern, handle) {
		if (pattern instanceof RegExp) {
			this.routes.push({pattern, handle});
		} else {
			console.warn("add route fail for", pattern, handle);
		}
	}

	start() {
		if( this.running ){
			return new new Promise((resolve, reject) => {
				resolve(this.origin);
			});
		}

		this.started = true;
		this.running = true;

		if (!this.keepAlive && (Platform.OS === 'android')) {
			AppState.addEventListener('change', this._handleAppStateChange.bind(this));
		}

		return FPStaticServer.start({
			port: this.port,
			root: this.root,
			localOnly: this.localOnly,
			keepAlive: this.keepAlive,
			uploadDir: this.uploadDir,
			tryAssets: this.tryAssets,
			timeoutInMillis: this.timeoutInMillis
		}).then((origin) => {
				this._origin = origin;
				return origin;
			});
	}

	stop() {
		this.running = false;
		return FPStaticServer.stop();
	}

	kill() {
		this.stop();
		DeviceEventEmitter.removeAllListeners('webServerRNRequest');
		this.started = false;
		this._origin = undefined;
		AppState.removeEventListener('change', this._handleAppStateChange.bind(this));
	}

	_handleAppStateChange(appState) {
		if (!this.started) {
			return;
		}

		if (appState === "active" && !this.running) {
			this.start();
		}

		if (appState === "background" && this.running) {
			this.stop();
		}

		if (appState === "inactive" && this.running) {
			this.stop();
		}
	}

	get origin() {
		return this._origin;
	}

}

export default StaticServer;
