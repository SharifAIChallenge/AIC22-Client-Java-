package ir.sharif.aic.hideandseek.client;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import ir.sharif.aic.hideandseek.ai.AI;
import ir.sharif.aic.hideandseek.ai.PoliceAI;
import ir.sharif.aic.hideandseek.ai.ThiefAI;
import ir.sharif.aic.hideandseek.api.grpc.AIProto.AgentType;
import ir.sharif.aic.hideandseek.api.grpc.AIProto.GameStatus;
import ir.sharif.aic.hideandseek.api.grpc.AIProto.GameView;
import ir.sharif.aic.hideandseek.api.grpc.AIProto.TurnType;
import ir.sharif.aic.hideandseek.api.grpc.GameHandlerGrpc;
import ir.sharif.aic.hideandseek.api.grpc.GameHandlerGrpc.GameHandlerBlockingStub;
import ir.sharif.aic.hideandseek.api.grpc.GameHandlerGrpc.GameHandlerStub;
import ir.sharif.aic.hideandseek.command.CommandImpl;
import ir.sharif.aic.hideandseek.config.ConfigLoader;
import java.util.Iterator;

public class ClientHandler {

    private final Channel channel;
    private final GameHandlerStub nonBlockingStub;
    private final GameHandlerBlockingStub blockingStub;
    private final CommandImpl commandImpl;

    private AI ai;
    private int turn = 1;
    private boolean hasMoved = false;

    public ClientHandler(Channel channel) {
        this.channel = channel;
        nonBlockingStub = GameHandlerGrpc.newStub(channel);
        blockingStub = GameHandlerGrpc.newBlockingStub(channel);
        commandImpl = new CommandImpl(ConfigLoader.getConfig().getToken());
    }

    public void handleClient() {
        var watchCommand = commandImpl.createWatchCommand();
        Iterator<GameView> gameViews;
        try {
            gameViews = blockingStub.watch(watchCommand);
            boolean isFirstTurn = true;
            while (gameViews.hasNext()) {
                GameView gameView = gameViews.next();
                if (turn != gameView.getTurn().getTurnNumber()) {
                    turn = gameView.getTurn().getTurnNumber();
                    hasMoved = false;
                }
                if (isFirstTurn) {
                    initialize(gameView);
                    isFirstTurn = false;
                } else if (gameView.getStatus() == GameStatus.ONGOING && canMove(gameView)) {
                    move(gameView);
                } else if (gameView.getStatus() == GameStatus.FINISHED) {
                    break;
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private boolean canMove(GameView gameView) {
        switch (gameView.getViewer().getType()) {
            case POLICE:
                if (gameView.getTurn().getTurnType() == TurnType.THIEF_TURN) {
                    return false;
                }
            case THIEF:
                if (gameView.getTurn().getTurnType() == TurnType.POLICE_TURN) {
                    return false;
                }
        }

        if (gameView.getTurn().getTurnNumber() == turn) {
            return !hasMoved;
        }
        return true;
    }

    public void sendMessage(String message) {
        var chatCommand = commandImpl.createChatCommand(message);
        blockingStub.sendMessage(chatCommand);
    }

    private void initialize(GameView gameView) {
        setAIMethod(gameView.getViewer().getType() == AgentType.POLICE);
        var startingNodeId = ai.getStartingNode(gameView);
        var declareReadinessCommand = commandImpl.declareReadinessCommand(startingNodeId);
        blockingStub.declareReadiness(declareReadinessCommand);
    }

    private void setAIMethod(boolean isPolice) {
        if (ai != null) {
            return;
        }
        var phone = new Phone(this);
        ai = isPolice ? new PoliceAI(phone) : new ThiefAI(phone);
    }

    private void move(GameView gameView) {
        var destinationNodeId = ai.move(gameView);
        var moveCommand = commandImpl.createMoveCommand(destinationNodeId);
        blockingStub.move(moveCommand);
        hasMoved = true;
    }

}