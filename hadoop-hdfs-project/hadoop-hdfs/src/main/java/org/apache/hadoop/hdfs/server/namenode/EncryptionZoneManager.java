package org.apache.hadoop.hdfs.server.namenode;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.fs.XAttr;
import org.apache.hadoop.fs.XAttrSetFlag;
import org.apache.hadoop.hdfs.XAttrHelper;
import org.apache.hadoop.hdfs.protocol.EncryptionZone;
import org.apache.hadoop.hdfs.protocol.SnapshotAccessControlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static org.apache.hadoop.hdfs.server.common.HdfsServerConstants
    .CRYPTO_XATTR_ENCRYPTION_ZONE;

/**
 * Manages the list of encryption zones in the filesystem.
 * <p/>
 * The EncryptionZoneManager has its own lock, but relies on the FSDirectory
 * lock being held for many operations. The FSDirectory lock should not be
 * taken if the manager lock is already held.
 */
public class EncryptionZoneManager {

  public static Logger LOG = LoggerFactory.getLogger(EncryptionZoneManager
      .class);

  /**
   * EncryptionZoneInt is the internal representation of an encryption zone. The
   * external representation of an EZ is embodied in an EncryptionZone and
   * contains the EZ's pathname.
   */
  private class EncryptionZoneInt {
    private final String keyName;
    private final long inodeId;

    EncryptionZoneInt(long inodeId, String keyName) {
      this.keyName = keyName;
      this.inodeId = inodeId;
    }

    String getKeyName() {
      return keyName;
    }

    long getINodeId() {
      return inodeId;
    }

  }

  private final Map<Long, EncryptionZoneInt> encryptionZones;
  private final FSDirectory dir;

  /**
   * Construct a new EncryptionZoneManager.
   *
   * @param dir Enclosing FSDirectory
   */
  public EncryptionZoneManager(FSDirectory dir) {
    this.dir = dir;
    encryptionZones = new HashMap<Long, EncryptionZoneInt>();
  }

  /**
   * Add a new encryption zone.
   * <p/>
   * Called while holding the FSDirectory lock.
   *
   * @param inodeId of the encryption zone
   * @param keyName encryption zone key name
   */
  void addEncryptionZone(Long inodeId, String keyName) {
    assert dir.hasWriteLock();
    final EncryptionZoneInt ez = new EncryptionZoneInt(inodeId, keyName);
    encryptionZones.put(inodeId, ez);
  }

  /**
   * Remove an encryption zone.
   * <p/>
   * Called while holding the FSDirectory lock.
   */
  void removeEncryptionZone(Long inodeId) {
    assert dir.hasWriteLock();
    encryptionZones.remove(inodeId);
  }

  /**
   * Returns true if an IIP is within an encryption zone.
   * <p/>
   * Called while holding the FSDirectory lock.
   */
  boolean isInAnEZ(INodesInPath iip)
      throws UnresolvedLinkException, SnapshotAccessControlException {
    assert dir.hasReadLock();
    return (getEncryptionZoneForPath(iip) != null);
  }

  /**
   * Returns the path of the EncryptionZoneInt.
   * <p/>
   * Called while holding the FSDirectory lock.
   */
  private String getFullPathName(EncryptionZoneInt ezi) {
    assert dir.hasReadLock();
    return dir.getInode(ezi.getINodeId()).getFullPathName();
  }

  /**
   * Get the key name for an encryption zone. Returns null if <tt>iip</tt> is
   * not within an encryption zone.
   * <p/>
   * Called while holding the FSDirectory lock.
   */
  String getKeyName(final INodesInPath iip) {
    assert dir.hasReadLock();
    EncryptionZoneInt ezi = getEncryptionZoneForPath(iip);
    if (ezi == null) {
      return null;
    }
    return ezi.getKeyName();
  }

  /**
   * Looks up the EncryptionZoneInt for a path within an encryption zone.
   * Returns null if path is not within an EZ.
   * <p/>
   * Must be called while holding the manager lock.
   */
  private EncryptionZoneInt getEncryptionZoneForPath(INodesInPath iip) {
    assert dir.hasReadLock();
    Preconditions.checkNotNull(iip);
    final INode[] inodes = iip.getINodes();
    for (int i = inodes.length - 1; i >= 0; i--) {
      final INode inode = inodes[i];
      if (inode != null) {
        final EncryptionZoneInt ezi = encryptionZones.get(inode.getId());
        if (ezi != null) {
          return ezi;
        }
      }
    }
    return null;
  }

  /**
   * Throws an exception if the provided path cannot be renamed into the
   * destination because of differing encryption zones.
   * <p/>
   * Called while holding the FSDirectory lock.
   *
   * @param srcIIP source IIP
   * @param dstIIP destination IIP
   * @param src    source path, used for debugging
   * @throws IOException if the src cannot be renamed to the dst
   */
  void checkMoveValidity(INodesInPath srcIIP, INodesInPath dstIIP, String src)
      throws IOException {
    assert dir.hasReadLock();
    final EncryptionZoneInt srcEZI = getEncryptionZoneForPath(srcIIP);
    final EncryptionZoneInt dstEZI = getEncryptionZoneForPath(dstIIP);
    final boolean srcInEZ = (srcEZI != null);
    final boolean dstInEZ = (dstEZI != null);
    if (srcInEZ) {
      if (!dstInEZ) {
        throw new IOException(
            src + " can't be moved from an encryption zone.");
      }
    } else {
      if (dstInEZ) {
        throw new IOException(
            src + " can't be moved into an encryption zone.");
      }
    }

    if (srcInEZ || dstInEZ) {
      Preconditions.checkState(srcEZI != null, "couldn't find src EZ?");
      Preconditions.checkState(dstEZI != null, "couldn't find dst EZ?");
      if (srcEZI != dstEZI) {
        final String srcEZPath = getFullPathName(srcEZI);
        final String dstEZPath = getFullPathName(dstEZI);
        final StringBuilder sb = new StringBuilder(src);
        sb.append(" can't be moved from encryption zone ");
        sb.append(srcEZPath);
        sb.append(" to encryption zone ");
        sb.append(dstEZPath);
        sb.append(".");
        throw new IOException(sb.toString());
      }
    }
  }

  /**
   * Create a new encryption zone.
   * <p/>
   * Called while holding the FSDirectory lock.
   */
  XAttr createEncryptionZone(String src, String keyName)
      throws IOException {
    assert dir.hasWriteLock();
    if (dir.isNonEmptyDirectory(src)) {
      throw new IOException(
          "Attempt to create an encryption zone for a non-empty directory.");
    }

    final INodesInPath srcIIP = dir.getINodesInPath4Write(src, false);
    EncryptionZoneInt ezi = getEncryptionZoneForPath(srcIIP);
    if (ezi != null) {
      throw new IOException("Directory " + src + " is already in an " +
          "encryption zone. (" + getFullPathName(ezi) + ")");
    }

    final XAttr ezXAttr = XAttrHelper
        .buildXAttr(CRYPTO_XATTR_ENCRYPTION_ZONE, keyName.getBytes());

    final List<XAttr> xattrs = Lists.newArrayListWithCapacity(1);
    xattrs.add(ezXAttr);
    // updating the xattr will call addEncryptionZone,
    // done this way to handle edit log loading
    dir.unprotectedSetXAttrs(src, xattrs, EnumSet.of(XAttrSetFlag.CREATE));
    return ezXAttr;
  }

  /**
   * Return the current list of encryption zones.
   * <p/>
   * Called while holding the FSDirectory lock.
   */
  List<EncryptionZone> listEncryptionZones() throws IOException {
    assert dir.hasReadLock();
    final List<EncryptionZone> ret =
        Lists.newArrayListWithExpectedSize(encryptionZones.size());
    for (EncryptionZoneInt ezi : encryptionZones.values()) {
      ret.add(new EncryptionZone(getFullPathName(ezi), ezi.getKeyName()));
    }
    return ret;
  }
}