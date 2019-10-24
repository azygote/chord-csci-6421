package org.gty.chord.model;

import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.math3.util.ArithmeticUtils;
import org.gty.chord.model.fingertable.FingerTableEntry;
import org.gty.chord.model.fingertable.FingerTableIdInterval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    private final Integer nodePort;
    private final long nodeId;
    private final Integer fingerRingSizeBits;
    private final byte[] sha1Hash;

    private final long fingerRingSize;
    private final long fingerRingHighestIndex;

    private final BasicChordNode self;
    private AtomicReference<BasicChordNode> predecessor;

    private final List<FingerTableEntry> fingerTable;
    private final Set<Long> keySet;
    private final Map<Long, BasicChordNode> nodeIdToBasicNodeObjectMap;

    private final RestTemplate restTemplate;

    String getNodeName() {
        return nodeName;
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

    public ChordNode(String nodeName, Integer nodePort, Integer fingerRingSizeBits, RestTemplate restTemplate) {
        this.nodeName = nodeName;
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
        this.restTemplate = restTemplate;
    }

    private byte[] calculateSha1Hash() {
        String nodeInfo = nodeName + ":" + nodePort;
        MessageDigest digest = DigestUtils.getSha1Digest();
        return DigestUtils.digest(digest, StringUtils.getBytesUtf8(nodeInfo));
    }

    private long truncateHashToNodeId() {
        String bits = new BigInteger(sha1Hash).toString(2);
        String truncatedBits = org.apache.commons.lang3.StringUtils.substring(bits, 1, fingerRingSizeBits + 1);
        return Long.parseLong(truncatedBits, 2);
    }

    private List<FingerTableEntry> initializeFingerTable() {
        List<FingerTableEntry> fingerTable = new CopyOnWriteArrayList<>();

        // initialize start for each finger table entry
        for (int i = 0; i < fingerRingSizeBits; ++i) {
            // start = (n + 2^i) mod 2^m
            long startFingerId = (nodeId + ArithmeticUtils.pow(2L, i)) % fingerRingSize;
            fingerTable.add(new FingerTableEntry(startFingerId, null, null));
        }

        // initialize interval for each finger table entry
        for (int i = 0; i < fingerRingSizeBits; ++i) {
            // interval = [(n + 2^i) mod 2^m, (n + 2^(i + 1)) mod 2^m)
            long endFingerId = (nodeId + ArithmeticUtils.pow(2L, i + 1)) % fingerRingSize;
            fingerTable.get(i).setInterval(new FingerTableIdInterval(fingerTable.get(i).getStartFingerId(), endFingerId));
        }

        // initialize successor to self
        fingerTable.get(0).setNodeId(nodeId);

        return fingerTable;
    }

    private BasicChordNode getImmediateSuccessor() {
        return nodeIdToBasicNodeObjectMap.get(fingerTable.get(0).getNodeId());
    }

    private void setImmediateSuccessor(BasicChordNode successor) {
        fingerTable.get(0).setNodeId(successor.getNodeId());
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
            return findSuccessorRemote(closetPrecedingNode, id);
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
            Long currentFinger = fingerTable.get(i).getNodeId();

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

    private BasicChordNode findSuccessorRemote(BasicChordNode targetNode, long id) {
        URI uri = UriComponentsBuilder.fromHttpUrl("http://localhost:" + targetNode.getNodePort() + "/api/find-successor")
            .queryParam("id", id)
            .encode(StandardCharsets.UTF_8)
            .build(true)
            .toUri();

        return restTemplate.getForObject(uri, BasicChordNode.class);
    }

    public BasicChordNode addKey(Long key) {
        BasicChordNode successorNode = findSuccessor(key);

        if (successorNode.getNodeId() == self.getNodeId()) {
            return assignKeyLocal(key);
        } else {
            return assignKeyRemote(successorNode, key);
        }
    }

    public BasicChordNode assignKeyLocal(Long key) {
        keySet.add(key);
        return self;
    }

    private BasicChordNode assignKeyRemote(BasicChordNode targetNode, long key) {
        URI uri = UriComponentsBuilder.fromHttpUrl("http://localhost:" + targetNode.getNodePort() + "/api/assign-key")
            .queryParam("key", key)
            .encode(StandardCharsets.UTF_8)
            .build(true)
            .toUri();

        return restTemplate.getForObject(uri, BasicChordNode.class);
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
        BasicChordNode successor = findSuccessorRemote(knownNode, nodeId);

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
    @Scheduled(fixedRate = 1_000L)
    public void stabilize() {
        BasicChordNode successor = getImmediateSuccessor();
        long successorId = successor.getNodeId();

        BasicChordNode x = getPredecessorRemote(successor);

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

        // successor.notify(n)
        logger.info("notifying successor {} about self {}", successor, self);
        notifyRemote(successor);
    }

    private BasicChordNode getPredecessorRemote(BasicChordNode targetNode) {
        URI uri = UriComponentsBuilder.fromHttpUrl("http://localhost:" + targetNode.getNodePort() + "/api/get-predecessor")
            .encode(StandardCharsets.UTF_8)
            .build(true)
            .toUri();

        return restTemplate.getForObject(uri, BasicChordNode.class);
    }

    /**
     * // n' thinks it might be our predecessor.
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

    private void notifyRemote(BasicChordNode targetNode) {
        URI uri = UriComponentsBuilder.fromHttpUrl("http://localhost:" + targetNode.getNodePort() + "/api/notify")
            .encode(StandardCharsets.UTF_8)
            .build(true)
            .toUri();

        restTemplate.postForObject(uri, self, String.class);
    }

    private AtomicInteger fixFingerNext = new AtomicInteger(0);

    /**
     * called periodically. refreshes finger table entries.
     * next stores the index of the next finger to fix.
     *      n.fix fingers()
     *          next = next + 1 ;
     *          if (next > m)
     *              next = 1 ;
     *          finger[next] = find successor(n + 2^(next−1));
     */
    @Scheduled(fixedRate = 1_500L)
    public void fixFingers() {
        fixFingerNext.incrementAndGet();

        fixFingerNext.getAndUpdate(value -> {
            if (value > fingerRingSizeBits - 1) {
                return 0;
            } else {
                return value;
            }
        });

        fingerTable.get(fixFingerNext.get())
            .setNodeId(findSuccessor((nodeId + ArithmeticUtils.pow(2L, fixFingerNext.get())) % fingerRingSize).getNodeId());
    }
}
