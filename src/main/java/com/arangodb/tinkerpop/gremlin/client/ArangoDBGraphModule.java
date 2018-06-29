package com.arangodb.tinkerpop.gremlin.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.arangodb.entity.DocumentField;
import com.arangodb.tinkerpop.gremlin.structure.ArangoDBEdge;
import com.arangodb.tinkerpop.gremlin.structure.ArangoDBVertex;
import com.arangodb.velocypack.VPackBuilder;
import com.arangodb.velocypack.VPackDeserializationContext;
import com.arangodb.velocypack.VPackDeserializer;
import com.arangodb.velocypack.VPackModule;
import com.arangodb.velocypack.VPackSerializationContext;
import com.arangodb.velocypack.VPackSerializer;
import com.arangodb.velocypack.VPackSetupContext;
import com.arangodb.velocypack.VPackSlice;
import com.arangodb.velocypack.exception.VPackException;

/**
 * Provide custom VelocyPack serializer/deserializer to correctly handle the custom fields. We need to do 
 * this because of the Map nature of the implementation (VelocyPack captures the Map identity first).
 * @author Horacio Hoyos Rodriguez (@horaciohoyosr)
 *
 */
public class ArangoDBGraphModule implements VPackModule {

	@Override
	public <C extends VPackSetupContext<C>> void setup(C context) {
		context.registerDeserializer(ArangoDBVertex.class, VERTEX_DESERIALIZER);
		context.registerSerializer(ArangoDBVertex.class, VERTEX_SERIALIZER);
		context.registerDeserializer(ArangoDBEdge.class, EDGE_DESERIALIZER);
		context.registerSerializer(ArangoDBEdge.class, EDGE_SERIALIZER);
	}
	
	@SuppressWarnings("serial")
	public static final Set<String> ARANGODB_GRAPH_FIELDS = new HashSet<String>() {{
		add(DocumentField.Type.ID.getSerializeName());
		add(DocumentField.Type.KEY.getSerializeName());
		add(DocumentField.Type.REV.getSerializeName());
		add(DocumentField.Type.FROM.getSerializeName());
		add(DocumentField.Type.TO.getSerializeName());
	}};
	
	public static final VPackDeserializer<ArangoDBVertex> VERTEX_DESERIALIZER = new VPackDeserializer<ArangoDBVertex>() {

		@Override
		public ArangoDBVertex deserialize(VPackSlice parent, VPackSlice vpack, VPackDeserializationContext context)
				throws VPackException {
			ArangoDBVertex vertex = new ArangoDBVertex();
			vertex._id(vpack.get(DocumentField.Type.ID.getSerializeName()).getAsString());
			vertex._key(vpack.get(DocumentField.Type.KEY.getSerializeName()).getAsString());
			vertex._rev(vpack.get(DocumentField.Type.REV.getSerializeName()).getAsString());
			Iterator<Entry<String, VPackSlice>> it = vpack.objectIterator();
			while (it.hasNext()){
				Entry<String, VPackSlice> entry = it.next();
				if (!ARANGODB_GRAPH_FIELDS.contains(entry.getKey())) {
					vertex.put(entry.getKey(), context.deserialize(entry.getValue(), Object.class));
				}
			}
			return vertex;
		}
		
	};
	
	public static final VPackSerializer<ArangoDBVertex> VERTEX_SERIALIZER = new VPackSerializer<ArangoDBVertex>() {
		
		@Override
		public void serialize(VPackBuilder builder, String attribute, ArangoDBVertex value,
				VPackSerializationContext context) throws VPackException {
			final Map<String, Object> doc = new HashMap<String, Object>();
			doc.put(DocumentField.Type.ID.getSerializeName(), value._id());
			doc.put(DocumentField.Type.KEY.getSerializeName(), value._key());
			doc.put(DocumentField.Type.REV.getSerializeName(), value._rev());
			doc.putAll(value);
			context.serialize(builder, attribute, doc);
		}
	};
	
	public static final VPackDeserializer<ArangoDBEdge> EDGE_DESERIALIZER = new VPackDeserializer<ArangoDBEdge>() {

		@Override
		public ArangoDBEdge deserialize(VPackSlice parent, VPackSlice vpack, VPackDeserializationContext context)
				throws VPackException {
			ArangoDBEdge edge = new ArangoDBEdge();
			edge._id(vpack.get(DocumentField.Type.ID.getSerializeName()).getAsString());
			edge._key(vpack.get(DocumentField.Type.KEY.getSerializeName()).getAsString());
			edge._rev(vpack.get(DocumentField.Type.REV.getSerializeName()).getAsString());
			edge._from(vpack.get(DocumentField.Type.FROM.getSerializeName()).getAsString());
			edge._to(vpack.get(DocumentField.Type.TO.getSerializeName()).getAsString());
			Iterator<Entry<String, VPackSlice>> it = vpack.objectIterator();
			while (it.hasNext()){
				Entry<String, VPackSlice> entry = it.next();
				if (!ARANGODB_GRAPH_FIELDS.contains(entry.getKey())) {
					edge.put(entry.getKey(), context.deserialize(entry.getValue(), Object.class));
				}
			}
			return edge;
		}
		
	};
	
	public static final VPackSerializer<ArangoDBEdge> EDGE_SERIALIZER = new VPackSerializer<ArangoDBEdge>() {
		
		@Override
		public void serialize(VPackBuilder builder, String attribute, ArangoDBEdge value,
				VPackSerializationContext context) throws VPackException {
			final Map<String, Object> doc = new HashMap<String, Object>();
			doc.put(DocumentField.Type.ID.getSerializeName(), value._id());
			doc.put(DocumentField.Type.KEY.getSerializeName(), value._key());
			doc.put(DocumentField.Type.REV.getSerializeName(), value._rev());
			doc.put(DocumentField.Type.TO.getSerializeName(), value._to());
			doc.put(DocumentField.Type.FROM.getSerializeName(), value._from());
			doc.putAll(value);
			context.serialize(builder, attribute, doc);
		}
	};

}
