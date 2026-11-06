package org.traccar.protocol;

import io.netty.handler.codec.string.StringEncoder;
import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;
import org.traccar.model.Command;

import jakarta.inject.Inject;

public class MicodusProtocol extends BaseProtocol {

    @Inject
    public MicodusProtocol(Config config) {
        setSupportedDataCommands(
            Command.TYPE_ENGINE_STOP,
            Command.TYPE_ENGINE_RESUME,
            Command.TYPE_POSITION_PERIODIC
        );
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new H02FrameDecoder(51));
                pipeline.addLast(new StringEncoder());
                pipeline.addLast(new H02ProtocolEncoder(MicodusProtocol.this));
                pipeline.addLast(new MicodusProtocolDecoder(MicodusProtocol.this));
            }
        });
    }
}
