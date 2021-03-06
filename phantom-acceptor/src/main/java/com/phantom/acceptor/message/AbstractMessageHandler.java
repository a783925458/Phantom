package com.phantom.acceptor.message;

import com.google.protobuf.InvalidProtocolBufferException;
import com.phantom.acceptor.dispatcher.DispatcherInstance;
import com.phantom.acceptor.dispatcher.DispatcherManager;
import com.phantom.acceptor.session.SessionManager;
import com.phantom.common.Constants;
import com.phantom.common.Message;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 公共消息处理器
 *
 * @author Jianfeng Wang
 * @since 2019/11/8 16:02
 */
@Slf4j
public abstract class AbstractMessageHandler<T> implements MessageHandler {

    protected DispatcherManager dispatcherManager;

    protected SessionManager sessionManager;

    protected ThreadPoolExecutor threadPoolExecutor;

    AbstractMessageHandler(DispatcherManager dispatcherManager, SessionManager sessionManager,
                           ThreadPoolExecutor threadPoolExecutor) {
        this.dispatcherManager = dispatcherManager;
        this.sessionManager = sessionManager;
        this.threadPoolExecutor = threadPoolExecutor;
    }

    @Override
    public void handleMessage(Message message, SocketChannel channel) {
        threadPoolExecutor.execute(() -> {
            try {
                switch (message.getMessageType()) {
                    case Constants.MESSAGE_TYPE_REQUEST:
                        String uid = sessionManager.getUid(channel);
                        T msg = parseMessage(message);
                        if (uid == null) {
                            log.info("找不到Session，发送消息失败, channel = {}", channel);
                            Message errorMessage = getErrorResponse(msg);
                            channel.writeAndFlush(errorMessage.getBuffer());
                            return;
                        }
                        sendRequestMessage(message, channel, msg);
                        break;
                    case Constants.MESSAGE_TYPE_RESPONSE:
                        sendResponseMessage(message);
                        break;
                    default:
                        break; // no-op
                }
            } catch (InvalidProtocolBufferException e) {
                log.error("序列化异常：", e);
            }
        });
    }

    /**
     * 解析消息
     *
     * @param message 消息
     * @return 解析后的消息
     * @throws InvalidProtocolBufferException 序列化失败
     */
    protected abstract T parseMessage(Message message) throws InvalidProtocolBufferException;


    /**
     * 获取这条消息是发送给谁的。
     *
     * @param message 消息
     * @return 用户Id
     */
    protected abstract String getReceiverId(T message);

    /**
     * 获取这条消息是响应给谁的。
     *
     * @param message 消息
     * @return 用户Id
     */
    protected abstract String getResponseUid(Message message) throws InvalidProtocolBufferException;

    /**
     * 获取错误响应
     *
     * @param message 请求消息
     * @return 响应
     */
    protected abstract Message getErrorResponse(T message);

    /**
     * 处理请求消息,直接把消息转发给分发系统
     *
     * @param message 消息
     * @param channel 客户端连接的channel
     */
    private void sendRequestMessage(Message message, SocketChannel channel, T msg) {
        String uid = getReceiverId(msg);
        if (!sendMessage(uid, message)) {
            log.info("将错误信息写回给客户端");
            channel.writeAndFlush(getErrorResponse(msg).getBuffer());
        }

    }

    /**
     * 处理响应消息,返回给客户端
     *
     * @param message 消息
     */
    private void sendResponseMessage(Message message) throws InvalidProtocolBufferException {
        String uid = getResponseUid(message);
        SocketChannel session = sessionManager.getChannel(uid);
        if (session != null) {
            log.info("将响应【{}】推送给客户端：uid = {}", Constants.requestTypeName(message.getRequestType()), uid);
            session.writeAndFlush(message.getBuffer());
        } else {
            log.info("将响应推送给客户端失败，找不到session");
        }
    }


    /**
     * 发送消息到分发系统
     *
     * @param uid     用户ID
     * @param message 消息
     */
    private boolean sendMessage(String uid, Message message) {
        log.info("将请求【{}】转发到分发系统, uid = {} ", Constants.requestTypeName(message.getRequestType()), uid);
        DispatcherInstance dispatcherInstance = dispatcherManager.chooseDispatcher(uid);
        if (dispatcherInstance == null) {
            log.error("无法找到接入系统，发送消息失败....");
            return false;
        }
        dispatcherInstance.sendMessage(message.getBuffer());
        return true;
    }

}
