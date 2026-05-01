#import "LiveKitC2CCallModule.h"
#import <DCUniModule/DCUniModule.h>
#import <AVFoundation/AVFoundation.h>
#import <UIKit/UIKit.h>
#import <MediaPlayer/MediaPlayer.h>

// LiveKit Swift Client（通过 Bridging Header 引入）
// LiveKitClient SDK 通过 CocoaPods 安装后自动可用

#pragma mark - LiveKitC2CCallModule Implementation

@interface LiveKitC2CCallModule ()

@property (nonatomic, strong) id room;                    // LKTRoom 实例
@property (nonatomic, copy) void (^eventCallbackBlock)(NSDictionary *result, BOOL keepAlive);
@property (nonatomic, assign) BOOL isVideoEnabled;
@property (nonatomic, assign) BOOL isAudioEnabled;
@property (nonatomic, strong) AVAudioPlayer *ringPlayer;

@end

@implementation LiveKitC2CCallModule

#pragma mark - 生命周期

- (instancetype)init {
    self = [super init];
    if (self) {
        _isVideoEnabled = YES;
        _isAudioEnabled = YES;
        [self announceForAccessibility:@"LiveKit 视频通话模块已初始化"];
    }
    return self;
}

- (void)dealloc {
    [self stopRing];
    [self disconnectRoom];
}

#pragma mark - 1. 发起 1v1 视频呼叫

- (void)startC2CVideoCall:(NSDictionary *)options callback:(void (^)(NSDictionary *result, BOOL keepAlive))callback {
    if (!options) {
        [self invokeError:callback msg:@"参数不能为空"];
        return;
    }

    NSString *wsURL = options[@"wsURL"] ?: @"";
    NSString *token = options[@"token"] ?: @"";

    if (wsURL.length == 0 || token.length == 0) {
        [self invokeError:callback msg:@"wsURL 和 token 不能为空"];
        return;
    }

    NSString *callRing = options[@"callRing"] ?: @"";
    
    // 播放呼叫铃声
    [self playRing:callRing];

    dispatch_async(dispatch_get_main_queue(), ^{
        [self connectToRoomWithWsURL:wsURL
                               token:token
                            isCaller:YES
                            callback:callback];
    });
}

#pragma mark - 2. 接听 1v1 视频通话

- (void)answerC2CVideoCall:(NSDictionary *)options callback:(void (^)(NSDictionary *result, BOOL keepAlive))callback {
    if (!options) {
        [self invokeError:callback msg:@"参数不能为空"];
        return;
    }

    NSString *wsURL = options[@"wsURL"] ?: @"";
    NSString *token = options[@"token"] ?: @"";

    if (wsURL.length == 0 || token.length == 0) {
        [self invokeError:callback msg:@"wsURL 和 token 不能为空"];
        return;
    }

    NSString *answerRing = options[@"answerRing"] ?: @"";
    [self playRing:answerRing];

    dispatch_async(dispatch_get_main_queue(), ^{
        [self connectToRoomWithWsURL:wsURL
                               token:token
                            isCaller:NO
                            callback:callback];
    });
}

#pragma mark - 3. 挂断

- (void)hangupCall {
    [self stopRing];
    [self sendEvent:@"onHangup" msg:@"已挂断"];
    [self disconnectRoom];
    [self announceForAccessibility:@"通话已挂断"];
}

#pragma mark - 4. 开关摄像头

- (void)enableVideo:(BOOL)enable {
    self.isVideoEnabled = enable;

    // 调用 LiveKit LocalParticipant 的 setCameraEnabled 方法
    if (self.room && [self.room respondsToSelector:@selector(localParticipant)]) {
        id localPart = [self.room performSelector:@selector(localParticipant)];
        if (localPart && [localPart respondsToSelector:@selector(setCameraEnabled:)]) {
            ((void (*)(id, SEL, BOOL))objc_msgSend)(localPart, @selector(setCameraEnabled:), enable);
        }
    }

    [self announceForAccessibility:enable ? @"摄像头已开启" : @"摄像头已关闭"];
}

#pragma mark - 5. 开关麦克风

- (void)enableAudio:(BOOL)enable {
    self.isAudioEnabled = enable;

    if (self.room && [self.room respondsToSelector:@selector(localParticipant)]) {
        id localPart = [self.room performSelector:@selector(localParticipant)];
        if (localPart && [localPart respondsToSelector:@selector(setMicrophoneEnabled:)]) {
            ((void (*)(id, SEL, BOOL))objc_msgSend)(localPart, @selector(setMicrophoneEnabled:), enable);
        }
    }

    [self announceForAccessibility:enable ? @"麦克风已开启" : @"麦克风已关闭"];
}

#pragma mark - 6. 切换前后摄像头

- (void)switchCamera:(NSString *)position {
    NSInteger cameraPos = [position isEqualToString:@"back"] ? 1 : 0; // 0=Front, 1=Back (LKTCameraPosition)

    if (self.room && [self.room respondsToSelector:@selector(localParticipant)]) {
        id localPart = [self.room performSelector:@selector(localParticipant)];
        if (localPart && [localPart respondsToSelector:@selector(setCameraPosition:)]) {
            ((void (*)(id, SEL, NSInteger))objc_msgSend)(localPart, @selector(setCameraPosition:), cameraPos);
        }
    }

    NSString *msg = [position isEqualToString:@"back"]
            ? @"已切换到后置摄像头"
            : @"已切换到前置摄像头";
    [self announceForAccessibility:msg];
}

#pragma mark - 7. 全局事件监听

- (void)onCallEvent:(void (^)(NSDictionary *result, BOOL keepAlive))callback {
    self.eventCallbackBlock = callback;
}

#pragma mark - 内部方法：连接房间（核心逻辑）

- (void)connectToRoomWithWsURL:(NSString *)wsURL
                         token:(NSString *)token
                      isCaller:(BOOL)isCaller
                      callback:(void (^)(NSDictionary *result, BOOL keepAlive))callback {

    [self disconnectRoom];

    NSURL *serverURL = [NSURL URLWithString:wsURL];

    // 使用 LiveKitClient SDK 连接房间
    // 通过 NSClassFromString 动态加载避免编译期强依赖
    Class roomClass = NSClassFromString(@"LKTRoom");
    if (!roomClass) {
        [self invokeError:callback msg:@"无法加载 LiveKit SDK，请确认 LiveKitC2CCall.framework 已正确集成"];
        return;
    }

    // 创建 Room 实例
    self.room = [[roomClass alloc] init];
    id roomObj = self.room;

    // 设置 delegate（使用 runtime 设置）
    if ([roomObj respondsToSelector:@selector(setDelegate:)]) {
        SEL delegateSel = @selector(setDelegate:);
        ((void (*)(id, SEL, id))objc_msgSend)(roomObj, delegateSel, self);
    }

    // 构建 ConnectOptions
    Class connectOptsClass = NSClassFromString(@"LKTConnectOptions");
    id connectOptions = nil;
    if (connectOptsClass) {
        connectOptions = [[connectOptsClass alloc] init];
        // autoSubscribe
        if ([connectOptions respondsToSelector:@selector(setAutoSubscribe:)]) {
            ((void (*)(id, SEL, BOOL))objc_msgSend)(connectOptions, @selector(setAutoSubscribe:), YES);
        }
    }

    // 构建 RoomOptions
    Class roomOptsClass = NSClassFromString(@"LKTRoomOptions");
    id roomOptions = nil;
    if (roomOptsClass) {
        roomOptions = [[roomOptsClass alloc] init];
    }

    // 调用 connect 方法
    SEL connectSel = NSSelectorFromString(@"connectWithURL:token:connectOptions:roomOptions:");
    if ([roomObj respondsToSelector:connectSel]) {
        IMP imp = [roomObj methodForSelector:connectSel];
        void (*connectFunc)(id, SEL, id, id, id, id) = (void *)imp;
        
        // 使用 PromiseKit 风格的回调处理连接结果
        // LiveKit SDK 返回 Promise<Room>，我们通过 done/catch 处理
        @try {
            id promiseResult = connectFunc(roomObj, connectSel, serverURL, token, connectOptions, roomOptions);
            
            if (promiseResult) {
                // 成功回调
                [self stopRing];
                NSString *successMsg = isCaller ? @"呼叫已发起" : @"已接听来电";
                [self invokeSuccess:callback msg:successMsg];
                [self announceForAccessibility:isCaller ? @"正在发起视频通话" : @"正在接听来电"];
                
                [self sendEvent:@"onConnected" msg:@"通话已接通"];
            } else {
                [self invokeError:callback msg:@"连接失败：返回结果为空"];
                [self sendEvent:@"onError" msg:@"连接失败：返回结果为空"];
            }
        } @catch (NSException *exception) {
            NSString *errorMsg = isCaller
                    ? [NSString stringWithFormat:@"发起呼叫失败: %@", exception.reason]
                    : [NSString stringWithFormat:@"接听失败: %@", exception.reason];
            [self invokeError:callback msg:errorMsg];
            [self sendEvent:@"onError" msg:errorMsg];
        }
    } else {
        [self invokeError:callback msg:@"Room.connect 方法不存在，SDK 版本可能不匹配"];
    }
}

/// 断开房间连接
- (void)disconnectRoom {
    if (self.room) {
        id roomObj = self.room;
        SEL disconnectSel = @selector(disconnect:);
        if ([roomObj respondsToSelector:disconnectSel]) {
            ((void (*)(id, SEL, id))objc_msgSend)(roomObj, disconnectSel, nil);
        } else if ([roomObj respondsToSelector:@selector(disconnect)]) {
            ((void (*)(id, SEL))objc_msgSend)(roomObj, @selector(disconnect));
        }
        self.room = nil;
    }
}

#pragma mark - 铃声播放

- (void)playRing:(NSString *)ringPath {
    [self stopRing];
    if (!ringPath || ringPath.length == 0) return;

    @try {
        NSData *data = [NSData dataWithContentsOfURL:[NSURL URLWithString:ringPath]];
        if (data) {
            NSError *error = nil;
            self.ringPlayer = [[AVAudioPlayer alloc] initWithData:data error:&error];
            if (error == nil && self.ringPlayer) {
                self.ringPlayer.numberOfLoops = -1;  // 循环播放
                [self.ringPlayer prepareToPlay];
                [self.ringPlayer play];
            }
        }
    } @catch (NSException *exception) {
        // 铃声播放失败不影响通话
    }
}

- (void)stopRing {
    if (self.ringPlayer) {
        [self.ringPlayer stop];
        self.ringPlayer = nil;
    }
}

#pragma mark - 辅助方法

/// 发送事件到 JS 回调
- (void)sendEvent:(NSString *)event msg:(NSString *)msg {
    if (!self.eventCallbackBlock) return;
    NSDictionary *result = @{
        @"event": event,
        @"msg": msg ?: @""
    };
    self.eventCallbackBlock(result, YES);  // keepAlive=YES 保持长驻
}

/// 调用成功回调
- (void)invokeSuccess:(void (^)(NSDictionary *, BOOL))callback msg:(NSString *)message {
    if (!callback) return;
    NSDictionary *result = @{@"code": @(0), @"msg": message ?: @""};
    callback(result, NO);  // keepAlive=NO 单次调用
}

/// 调用错误回调
- (void)invokeError:(void (^)(NSDictionary *, BOOL))callback msg:(NSString *)message {
    if (!callback) return;
    NSDictionary *result = @{@"code": @(-1), @"msg": message ?: @""};
    callback(result, NO);
}

/// 无障碍：通过系统朗读状态变化
- (void)announceForAccessibility:(NSString *)message {
    UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, message);
}

@end
