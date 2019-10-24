package org.gty.chord.model;

import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.math3.util.ArithmeticUtils;
import org.gty.chord.exception.ChordHealthCheckException;
import org.gty.chord.model.fingertable.FingerTableEntry;
import org.gty.chord.model.fingertable.FingerTableIdInterval;
import org.gty.chord.rest.ChordNodeRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ChordNode {

    private static final Logger logger = LoggerFactory.getLogger(ChordNode.class);

    private final String nodeName;
    private final String nodeAddress;
    private final Integer nodePort;
    private final long nodeId;
    private final Integer fingerRingSizeBits;
    private final byte[] sha1Hash;

    private final long fingerRingSize;
    private final long fingerRingHighestIndex;

    private final BasicChordNode self;
    private AtomicReference<BasicChordNode> predecessor;

    private final List<FingerTableEntry> fingerTable;
    private AtomicInteger fixFingerNext = new AtomicInteger(0);
    private final Set<Long> keySet;
    private final Map<Long, BasicChordNode> nodeIdToBasicNodeObjectMap;

    private final ChordNodeRestClient chordNodeRestClient;

    String getNodeName() {
        return nodeName;
    }

    String getNodeAddress() {
        return nodeAddress;
    }

    Integer getNodePort() {
        return nodePort;
    }

    long getNodeId() {
        return nodeId;
    }

    public List<FingerTableEntry> getFingerTable() {
        return fingerTable;
    }

    public ChordNode(String nodeName,
                     String nodeAddress,
                     Integer nodePort,
                     Integer fingerRingSizeBits,
                     ChordNodeRestClient chordNodeRestClient) {
        this.nodeName = nodeName;
        this.nodeAddress = nodeAddress;
        this.nodePort = nodePort;
        this.fingerRingSizeBits = fingerRingSizeBits;

        fingerRingSize = ArithmeticUtils.pow(2L, fingerRingSizeBits);
        fingerRingHighestIndex = fingerRingSize - 1L;

        sha1Hash = calculateSha1Hash();
        nodeId = truncateHashToNodeId();

        predecessor = new AtomicReference<>();
        self = new BasicChordNode(this);

        fingerTable = initializeFingerTable();
        keySet = Sets.newConcurrentHashSet();
        nodeIdToBasicNodeObjectMap = initializeNodeIdToBasicNodeObjectMap();

        this.chordNodeRestClient = chordNodeRestClient;
    }

    private byte[] calculateSha1Hash() {
        String nodeInfo = nodeName + ":" + nodeAddress + ":" + nodePort;
        MessageDigest digest = DigestUtils.getSha1Digest();
        return DigestUtils.digest(digest, StringUtils.getBytesUtf8(nodeInfo));
    }

    private long truncateHashToNodeId() {
        String bits = new BigInteger(sha1Hash).toString(2);
        String truncatedBits = org.apache.commons.lang3.StringUtils.substring(bits, 37, fingerRingSizeBits + 37);
        return Long.parseLong(truncatedBits, 2);
    }

    private List<FingerTableEntry> initializeFingerTable() {
        List<FingerTableEntry> fingerTable = new CopyOnWriteArrayList<>();

        for (int i = 0; i < fingerRingSizeBits; ++i) {
            // initialize start for each finger table entry
            // start = (n + 2^i) mod 2^m
            long startFingerId = (nodeId + ArithmeticUtils.pow(2L, i)) % fingerRingSize;
            fingerTable.add(new FingerTableEntry(startFingerId, null, new AtomicReference<>()));

            // initialize interval for each finger table entry
            // interval = [(n + 2^i) mod 2^m, (n + 2^(i + 1)) mod 2^m)
            long endFingerId = (nodeId + ArithmeticUtils.pow(2L, i + 1)) % fingerRingSize;
            fingerTable.get(i).setInterval(new FingerTableIdInterval(fingerTable.get(i).getStartFingerId(), endFingerId));
        }

        // initialize successor to self
        fingerTable.get(0).getNodeId().set(nodeId);

        return fingerTable;
    }

    private BasicChordNode getImmediateSuccessor() {
        return nodeIdToBasicNodeObjectMap.get(fingerTable.get(0).getNodeId().get());
    }

    private void setImmediateSuccessor(BasicChordNode successor) {
        fingerTable.get(0).getNodeId().set(successor.getNodeId());
    }

    public BasicChordNode getPredecessor() {
        return predecessor.get();
    }

    private void setPredecessor(BasicChordNode predecessor) {
        this.predecessor.set(predecessor);
    }

    private Map<Long, BasicChordNode> initializeNodeIdToBasicNodeObjectMap() {
        Map<Long, BasicChordNode> nodeIdToBasicNodeObjectMap
            = new ConcurrentHashMap<>();
        nodeIdToBasicNodeObjectMap.put(nodeId, self);
        return nodeIdToBasicNodeObjectMap;
    }

    public BasicChordNode getBasicChordNode() {
        return nodeIdToBasicNodeObjectMap.get(nodeId);
    }

    public Set<Long> getKeySet() {
        return keySet;
    }

    /**
     * ask node n to find the successor of id
     *
     * n.find-successor(id)
     *      if (id ∈ (n,successor])
     *          return successor;
     *      else
     *          n' = closest-preceding-node(id);
     *          return n'.find-successor(id);
     *
     * @param id identifier to be found
     * @return successor of id
     */
    public BasicChordNode findSuccessor(long id) {
        BasicChordNode successor = getImmediateSuccessor();
        long successorId = successor.getNodeId();

        if ( ( (nodeId <= successorId) && Range.openClosed(nodeId, successorId).contains(id) )
            || ( (nodeId > successorId) && (Range.openClosed(nodeId, fingerRingHighestIndex).contains(id) || Range.closed(0L, successorId).contains(id)) ) ) {
            return nodeIdToBasicNodeObjectMap.get(successorId);
        }

        BasicChordNode closetPrecedingNode = closestPrecedingNode(id);

        if (closetPrecedingNode.getNodeId() == nodeId) {
            return successor;
        } else {
            return chordNodeRestClient.findSuccessorRemote(closetPrecedingNode, id);
        }
    }

    /**
     * search the local table for the highest predecessor of id
     *
     * n.closest-preceding-node(id)
     *      for i = m downto 1
     *          if (finger[i] ∈ (n,id))
     *              return finger[i];
     *      return n;
     *
     * @param id identifier to be found
     * @return the highest predecessor of id from finger table
     */
    private BasicChordNode closestPrecedingNode(long id) {
        for (int i = fingerRingSizeBits - 1; i >= 0; --i) {
            Long currentFinger = fingerTable.get(i).getNodeId().get();

            if (currentFinger != null) {
                if (nodeId < id) {
                    if (Range.open(nodeId, id).contains(currentFinger)) {
                        return nodeIdToBasicNodeObjectMap.get(currentFinger);
                    }
                } else {
                    if (Range.openClosed(nodeId, fingerRingHighestIndex).contains(currentFinger)
                        || Range.closedOpen(0L, id).contains(currentFinger)) {
                        return nodeIdToBasicNodeObjectMap.get(currentFinger);
                    }
                }
            }
        }

        return self;
    }

    public BasicChordNode addKey(Long key) {
        BasicChordNode successorNode = findSuccessor(key);

        return chordNodeRestClient.assignKeyRemote(successorNode, key);
    }

    public BasicChordNode assignKey(Long key) {
        keySet.add(key);
        return self;
    }

    /**
     * join a Chord ring containing node n'
     *      n.join(n')
     *          predecessor = nil;
     *          successor = n'.find-successor(n);
     *
     * @param knownNode node to be joined
     */
    public void joiningToKnownNode(BasicChordNode knownNode) {
        BasicChordNode successor = chordNodeRestClient.findSuccessorRemote(knownNode, nodeId);

        setImmediateSuccessor(successor);

        nodeIdToBasicNodeObjectMap.putIfAbsent(successor.getNodeId(), successor);
    }

    /**
     * called periodically. verifies n’s immediate
     * successor, and tells the successor about n.
     *      n.stabilize()
     *          x = successor.predecessor;
     *          if (x ∈ (n,successor))
     *              successor = x;
     *          successor.notify(n);
     *
     */
    public void stabilize() {
        BasicChordNode successor = getImmediateSuccessor();
        long successorId = successor.getNodeId();

        try {
            BasicChordNode x = chordNodeRestClient.getPredecessorRemote(successor);

            if (x != null) {
                nodeIdToBasicNodeObjectMap.putIfAbsent(x.getNodeId(), x);

                if (nodeId < successorId) {
                    if (Range.open(nodeId, successorId).contains(x.getNodeId())) {
                        setImmediateSuccessor(x);
                    }
                } else {
                    if (Range.openClosed(nodeId, fingerRingHighestIndex).contains(x.getNodeId())
                        || Range.closedOpen(0L, successorId).contains(x.getNodeId())) {
                        setImmediateSuccessor(x);
                    }
                }
            }
        } catch (RestClientException ex) {
            // if successor is not alive
            // set successor to self
            setImmediateSuccessor(self);
            successor = self;
        }

        // successor.notify(n)
        logger.info("notifying successor {} about self {}", successor, self);
        try {
            chordNodeRestClient.notifyRemote(self, successor);
        } catch (RestClientException ex) {
            // if the successor is not alive
            // set successor to self
            setImmediateSuccessor(self);
            successor = self;
            chordNodeRestClient.notifyRemote(self, successor);
        }
    }

    /**
     * n' thinks it might be our predecessor.
     *      n.notify(n')
     *          if (predecessor is nil or n' ∈ (predecessor, n))
     *              predecessor = n';
     *
     * @param incomingNode node to be notified
     */
    public void notify(BasicChordNode incomingNode) {
        BasicChordNode predecessor = getPredecessor();

        if (predecessor == null) {
            setPredecessor(incomingNode);
            nodeIdToBasicNodeObjectMap.putIfAbsent(incomingNode.getNodeId(), incomingNode);
            return;
        }

        if (predecessor.getNodeId() < nodeId) {
            if (Range.open(predecessor.getNodeId(), nodeId).contains(incomingNode.getNodeId())) {
                setPredecessor(incomingNode);
                nodeIdToBasicNodeObjectMap.putIfAbsent(incomingNode.getNodeId(), incomingNode);
            }
        } else {
            if (Range.openClosed(predecessor.getNodeId(), fingerRingHighestIndex).contains(incomingNode.getNodeId())
                || Range.closedOpen(0L, nodeId).contains(incomingNode.getNodeId())) {
                setPredecessor(incomingNode);
                nodeIdToBasicNodeObjectMap.putIfAbsent(incomingNode.getNodeId(), incomingNode);
            }
        }
    }

    /**
     * called periodically. refreshes finger table entries.
     * next stores the index of the next finger to fix.
     *      n.fix fingers()
     *          next = next + 1 ;
     *          if (next > m)
     *              next = 1 ;
     *          finger[next] = find-successor(n + 2^(next−1));
     */
    public void fixFingers() {
        fixFingerNext.incrementAndGet();

        fixFingerNext.getAndUpdate(value -> {
            if (value > fingerRingSizeBits - 1) {
                return 0;
            } else {
                return value;
            }
        });

        BasicChordNode node = findSuccessor((nodeId + ArithmeticUtils.pow(2L, fixFingerNext.get())) % fingerRingSize);

        fingerTable.get(fixFingerNext.get())
            .getNodeId().set(node.getNodeId());

        nodeIdToBasicNodeObjectMap.putIfAbsent(node.getNodeId(), node);
    }

    /**
     * called periodically. checks whether predecessor has failed.
     *      n.check predecessor()
     *          if (predecessor has failed)
     *              predecessor = nil;
     */
    public void checkPredecessor() {
        BasicChordNode predecessor = getPredecessor();
        if (predecessor != null) {
            try {
                chordNodeRestClient.healthCheck(predecessor);
            } catch (ChordHealthCheckException ex) {
                setPredecessor(null);
            }
        }
    }
}
