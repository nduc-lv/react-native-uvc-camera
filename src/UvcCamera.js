// @flow
import React from 'react';
import PropTypes from 'prop-types';
import { mapValues } from 'lodash';
import {
  findNodeHandle,
  Platform,
  NativeModules,
  requireNativeComponent,
  View,
  ActivityIndicator,
  Text,
  StyleSheet,
} from 'react-native';

import type { FaceFeature } from './FaceDetector';

import { requestPermissions } from './handlePermissions';

const styles = StyleSheet.create({
  authorizationContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  notAuthorizedText: {
    textAlign: 'center',
    fontSize: 16,
  },
});

type PictureOptions = {
  quality?: number,
  base64?: boolean,
  mirrorImage?: boolean,
  exif?: boolean,
  width?: number,
  fixOrientation?: boolean,
  forceUpOrientation?: boolean,
};

type TrackedFaceFeature = FaceFeature & {
  faceID?: number,
};

type TrackedTextFeature = {
  type: string,
  bounds: {
    size: {
      width: number,
      height: number,
    },
    origin: {
      x: number,
      y: number,
    },
  },
  value: string,
  components: Array<TrackedTextFeature>,
};

type RecordingOptions = {
  maxDuration?: number,
  maxFileSize?: number,
  quality?: number | string,
  codec?: string,
  mute?: boolean,
};

type EventCallbackArgumentsType = {
  nativeEvent: Object,
};

type PropsType = typeof View.props & {
  rotation?: number,
  zoom?: number,
  ratio?: string,
  focusDepth?: number,
  type?: number | string,
  onCameraReady?: Function,
  onBarCodeRead?: Function,
  onGoogleVisionBarcodesDetected?: Function,
  faceDetectionMode?: number,
  flashMode?: number | string,
  barCodeTypes?: Array<string>,
  googleVisionBarcodeType?: number,
  whiteBalance?: number | string,
  faceDetectionLandmarks?: number,
  autoFocus?: string | boolean | number,
  faceDetectionClassifications?: number,
  onFacesDetected?: ({ faces: Array<TrackedFaceFeature> }) => void,
  onTextRecognized?: ({ textBlocks: Array<TrackedTextFeature> }) => void,
  captureAudio?: boolean,
  useCamera2Api?: boolean,
  playSoundOnCapture?: boolean,
};

type StateType = {
  isAuthorized: boolean,
  isAuthorizationChecked: boolean,
};

type Status = 'READY' | 'PENDING_AUTHORIZATION' | 'NOT_AUTHORIZED';

const CameraStatus = {
  READY: 'READY',
  PENDING_AUTHORIZATION: 'PENDING_AUTHORIZATION',
  NOT_AUTHORIZED: 'NOT_AUTHORIZED',
};

const CameraManager: Object = NativeModules.UvcCameraManager ||
  NativeModules.UvcCameraModule || {
    stubbed: true,
    Type: {
      back: 1,
    },
    AutoFocus: {
      on: 1,
    },
    FlashMode: {
      off: 1,
    },
    WhiteBalance: {},
    BarCodeType: {},
    FaceDetection: {
      fast: 1,
      Mode: {},
      Landmarks: {
        none: 0,
      },
      Classifications: {
        none: 0,
      },
    },
    GoogleVisionBarcodeDetection: {
      BarcodeType: 0,
    },
  };

const EventThrottleMs = 500;

export default class Camera extends React.Component<PropsType, StateType> {
  static Constants = {
    Type: CameraManager.Type,
    FlashMode: CameraManager.FlashMode,
    AutoFocus: CameraManager.AutoFocus,
    WhiteBalance: CameraManager.WhiteBalance,
    VideoQuality: CameraManager.VideoQuality,
    VideoCodec: CameraManager.VideoCodec,
    BarCodeType: CameraManager.BarCodeType,
    GoogleVisionBarcodeDetection: CameraManager.GoogleVisionBarcodeDetection,
    FaceDetection: CameraManager.FaceDetection,
    CameraStatus,
  };

  // Values under keys from this object will be transformed to native options
  static ConversionTables = {
    type: CameraManager.Type,
    flashMode: CameraManager.FlashMode,
    autoFocus: CameraManager.AutoFocus,
    whiteBalance: CameraManager.WhiteBalance,
    faceDetectionMode: (CameraManager.FaceDetection || {}).Mode,
    faceDetectionLandmarks: (CameraManager.FaceDetection || {}).Landmarks,
    faceDetectionClassifications: (CameraManager.FaceDetection || {}).Classifications,
    googleVisionBarcodeType: (CameraManager.GoogleVisionBarcodeDetection || {}).BarcodeType,
  };

  static propTypes = {
    rotation: PropTypes.number,
    zoom: PropTypes.number,
    ratio: PropTypes.string,
    focusDepth: PropTypes.number,
    onMountError: PropTypes.func,
    onCameraReady: PropTypes.func,
    onBarCodeRead: PropTypes.func,
    onGoogleVisionBarcodesDetected: PropTypes.func,
    onFacesDetected: PropTypes.func,
    onTextRecognized: PropTypes.func,
    faceDetectionMode: PropTypes.number,
    faceDetectionLandmarks: PropTypes.number,
    faceDetectionClassifications: PropTypes.number,
    barCodeTypes: PropTypes.arrayOf(PropTypes.string),
    googleVisionBarcodeType: PropTypes.number,
    type: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
    flashMode: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
    whiteBalance: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
    autoFocus: PropTypes.oneOfType([PropTypes.string, PropTypes.number, PropTypes.bool]),
    permissionDialogTitle: PropTypes.string,
    permissionDialogMessage: PropTypes.string,
    notAuthorizedView: PropTypes.element,
    pendingAuthorizationView: PropTypes.element,
    captureAudio: PropTypes.bool,
    useCamera2Api: PropTypes.bool,
    playSoundOnCapture: PropTypes.bool,
  };

  static defaultProps: Object = {
    rotation: 0,
    zoom: 0,
    ratio: '4:3',
    focusDepth: 0,
    type: CameraManager.Type.back,
    autoFocus: CameraManager.AutoFocus.on,
    flashMode: CameraManager.FlashMode.off,
    whiteBalance: CameraManager.WhiteBalance.auto,
    faceDetectionMode: (CameraManager.FaceDetection || {}).fast,
    barCodeTypes: Object.values(CameraManager.BarCodeType),
    googleVisionBarcodeType: ((CameraManager.GoogleVisionBarcodeDetection || {}).BarcodeType || {})
      .None,
    faceDetectionLandmarks: ((CameraManager.FaceDetection || {}).Landmarks || {}).none,
    faceDetectionClassifications: ((CameraManager.FaceDetection || {}).Classifications || {}).none,
    permissionDialogTitle: '',
    permissionDialogMessage: '',
    notAuthorizedView: (
      <View style={styles.authorizationContainer}>
        <Text style={styles.notAuthorizedText}>Camera not authorized</Text>
      </View>
    ),
    pendingAuthorizationView: (
      <View style={styles.authorizationContainer}>
        <ActivityIndicator size="small" />
      </View>
    ),
    captureAudio: false,
    useCamera2Api: false,
    playSoundOnCapture: false,
  };

  _cameraRef: ?Object;
  _cameraHandle: ?number;
  _lastEvents: { [string]: string };
  _lastEventsTimes: { [string]: Date };

  constructor(props: PropsType) {
    super(props);
    this._lastEvents = {};
    this._lastEventsTimes = {};
    this.state = {
      isAuthorized: false,
      isAuthorizationChecked: false,
    };
  }

  async takePictureAsync(options?: PictureOptions) {
    if (!options) {
      options = {};
    }
    if (!options.quality) {
      options.quality = 1;
    }
    return await CameraManager.takePicture(options, this._cameraHandle);
  }

  async getSupportedRatiosAsync() {
    if (Platform.OS === 'android') {
      return await CameraManager.getSupportedRatios(this._cameraHandle);
    } else {
      throw new Error('Ratio is not supported on iOS');
    }
  }

  async recordAsync(options?: RecordingOptions) {
    if (!options || typeof options !== 'object') {
      options = {};
    } else if (typeof options.quality === 'string') {
      options.quality = Camera.Constants.VideoQuality[options.quality];
    }
    return await CameraManager.record(options, this._cameraHandle);
  }

  stopRecording() {
    CameraManager.stopRecording(this._cameraHandle);
  }

  _onMountError = ({ nativeEvent }: EventCallbackArgumentsType) => {
    if (this.props.onMountError) {
      this.props.onMountError(nativeEvent);
    }
  };

  _onCameraReady = () => {
    if (this.props.onCameraReady) {
      this.props.onCameraReady();
    }
  };

  _onObjectDetected = (callback: ?Function) => ({ nativeEvent }: EventCallbackArgumentsType) => {
    const { type } = nativeEvent;

    if (
      this._lastEvents[type] &&
      this._lastEventsTimes[type] &&
      JSON.stringify(nativeEvent) === this._lastEvents[type] &&
      new Date() - this._lastEventsTimes[type] < EventThrottleMs
    ) {
      return;
    }

    if (callback) {
      callback(nativeEvent);
      this._lastEventsTimes[type] = new Date();
      this._lastEvents[type] = JSON.stringify(nativeEvent);
    }
  };

  _setReference = (ref: ?Object) => {
    if (ref) {
      this._cameraRef = ref;
      this._cameraHandle = findNodeHandle(ref);
    } else {
      this._cameraRef = null;
      this._cameraHandle = null;
    }
  };

  async UNSAFE_componentWillMount() {
    const hasVideoAndAudio = this.props.captureAudio;
    const isAuthorized = await requestPermissions(
      hasVideoAndAudio,
      CameraManager,
      this.props.permissionDialogTitle,
      this.props.permissionDialogMessage,
    );
    this.setState({ isAuthorized, isAuthorizationChecked: true });
  }

  getStatus = (): Status => {
    const { isAuthorized, isAuthorizationChecked } = this.state;
    if (isAuthorizationChecked === false) {
      return CameraStatus.PENDING_AUTHORIZATION;
    }
    return isAuthorized ? CameraStatus.READY : CameraStatus.NOT_AUTHORIZED;
  };

  // FaCC = Function as Child Component;
  hasFaCC = (): * => typeof this.props.children === 'function';

  renderChildren = (): * => {
    if (this.hasFaCC()) {
      return this.props.children({ camera: this, status: this.getStatus() });
    }
    return this.props.children;
  };

  render() {
    const nativeProps = this._convertNativeProps(this.props);
    console.log("React-Natve", nativeProps);
    if (this.state.isAuthorized || this.hasFaCC()) {
      console.log("Render Children");
      return (
        <RNCamera
          {...nativeProps}
          ref={this._setReference}
          onMountError={this._onMountError}
          onCameraReady={this._onCameraReady}
          onGoogleVisionBarcodesDetected={this._onObjectDetected(
            this.props.onGoogleVisionBarcodesDetected,
          )}
          onBarCodeRead={this._onObjectDetected(this.props.onBarCodeRead)}
          onFacesDetected={this._onObjectDetected(this.props.onFacesDetected)}
          onTextRecognized={this._onObjectDetected(this.props.onTextRecognized)}
        >
          {this.renderChildren()}
        </RNCamera>
      );
    } else if (!this.state.isAuthorizationChecked) {
      console.log("React-Native not checking for authorization", this.state.isAuthorizationChecked);
      return this.props.pendingAuthorizationView;
    } else {
      console.log("React-Native not Authorized", this.state.isAuthorized);
      return this.props.notAuthorizedView;
    }
  }

  _convertNativeProps(props: PropsType) {
    const newProps = mapValues(props, this._convertProp);

    if (props.onBarCodeRead) {
      newProps.barCodeScannerEnabled = true;
    }

    if (props.onGoogleVisionBarcodesDetected) {
      newProps.googleVisionBarcodeDetectorEnabled = true;
    }

    if (props.onFacesDetected) {
      newProps.faceDetectorEnabled = true;
    }

    if (props.onTextRecognized) {
      newProps.textRecognizerEnabled = true;
    }

    if (Platform.OS === 'ios') {
      delete newProps.googleVisionBarcodeType;
      delete newProps.googleVisionBarcodeDetectorEnabled;
      delete newProps.ratio;
      delete newProps.textRecognizerEnabled;
    }

    return newProps;
  }

  _convertProp(value: *, key: string): * {
    if (typeof value === 'string' && Camera.ConversionTables[key]) {
      return Camera.ConversionTables[key][value];
    }

    return value;
  }
}

export const Constants = Camera.Constants;

const RNCamera = requireNativeComponent('UvcCamera', Camera, {
  nativeOnly: {
    accessibilityComponentType: true,
    accessibilityLabel: true,
    accessibilityLiveRegion: true,
    barCodeScannerEnabled: true,
    googleVisionBarcodeDetectorEnabled: true,
    faceDetectorEnabled: true,
    textRecognizerEnabled: true,
    importantForAccessibility: true,
    onBarCodeRead: true,
    onGoogleVisionBarcodesDetected: true,
    onCameraReady: true,
    onFaceDetected: true,
    onLayout: true,
    onMountError: true,
    renderToHardwareTextureAndroid: true,
    testID: true,
  },
});