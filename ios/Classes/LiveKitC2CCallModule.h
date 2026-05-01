#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface LiveKitC2CCallModule : NSObject

/// 发起一对一视频呼叫
- (void)startC2CVideoCall:(NSDictionary *)options callback:(void (^)(NSDictionary *result, BOOL keepAlive))callback;

/// 接听一对一视频通话
- (void)answerC2CVideoCall:(NSDictionary *)options callback:(void (^)(NSDictionary *result, BOOL keepAlive))callback;

/// 挂断通话
- (void)hangupCall;

/// 开关摄像头
- (void)enableVideo:(BOOL)enable;

/// 开关麦克风
- (void)enableAudio:(BOOL)enable;

/// 切换前后摄像头
- (void)switchCamera:(NSString *)position;

/// 全局事件监听
- (void)onCallEvent:(void (^)(NSDictionary *result, BOOL keepAlive))callback;

@end

NS_ASSUME_NONNULL_END
