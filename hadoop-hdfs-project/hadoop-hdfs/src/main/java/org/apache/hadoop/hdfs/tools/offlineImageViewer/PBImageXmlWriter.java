/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.tools.offlineImageViewer;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.TimeZone;

import com.google.protobuf.ByteString;
import org.apache.commons.codec.binary.Hex;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.protocol.proto.ClientNamenodeProtocolProtos.CacheDirectiveInfoExpirationProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientNamenodeProtocolProtos.CacheDirectiveInfoProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientNamenodeProtocolProtos.CachePoolInfoProto;
import org.apache.hadoop.hdfs.protocol.proto.HdfsProtos.BlockProto;
import org.apache.hadoop.hdfs.protocol.proto.XAttrProtos;
import org.apache.hadoop.hdfs.server.namenode.FSImageFormatPBINode;
import org.apache.hadoop.hdfs.server.namenode.FSImageFormatProtobuf.SectionName;
import org.apache.hadoop.hdfs.server.namenode.FSImageUtil;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.CacheManagerSection;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.FileSummary;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.FilesUnderConstructionSection.FileUnderConstructionEntry;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeDirectorySection;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection.AclFeatureProto;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection.INodeDirectory;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection.INodeSymlink;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeReferenceSection;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.NameSystemSection;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.SecretManagerSection;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.SnapshotDiffSection;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.SnapshotSection;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.StringTableSection;
import org.apache.hadoop.hdfs.util.XMLUtils;
import org.apache.hadoop.util.LimitInputStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.hadoop.util.VersionInfo;

import static org.apache.hadoop.hdfs.server.namenode.FSImageFormatPBINode.XATTR_NAMESPACE_MASK;
import static org.apache.hadoop.hdfs.server.namenode.FSImageFormatPBINode.XATTR_NAMESPACE_OFFSET;
import static org.apache.hadoop.hdfs.server.namenode.FSImageFormatPBINode.XATTR_NAMESPACE_EXT_MASK;
import static org.apache.hadoop.hdfs.server.namenode.FSImageFormatPBINode.XATTR_NAMESPACE_EXT_OFFSET;
import static org.apache.hadoop.hdfs.server.namenode.FSImageFormatPBINode.XATTR_NAME_OFFSET;
import static org.apache.hadoop.hdfs.server.namenode.FSImageFormatPBINode.XATTR_NAME_MASK;

/**
 * PBImageXmlWriter walks over an fsimage structure and writes out
 * an equivalent XML document that contains the fsimage's components.
 */
@InterfaceAudience.Private
public final class PBImageXmlWriter {
  private final Configuration conf;
  private final PrintStream out;
  private final SimpleDateFormat isoDateFormat;
  private String[] stringTable;

  public static SimpleDateFormat createSimpleDateFormat() {
    SimpleDateFormat format =
      new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return format;
  }

  public PBImageXmlWriter(Configuration conf, PrintStream out) {
    this.conf = conf;
    this.out = out;
    this.isoDateFormat = createSimpleDateFormat();
  }

  public void visit(RandomAccessFile file) throws IOException {
    if (!FSImageUtil.checkFileFormat(file)) {
      throw new IOException("Unrecognized FSImage");
    }

    FileSummary summary = FSImageUtil.loadSummary(file);
    try (FileInputStream fin = new FileInputStream(file.getFD())) {
      out.print("<?xml version=\"1.0\"?>\n<fsimage>");

      out.print("<version>");
      o("layoutVersion", summary.getLayoutVersion());
      o("onDiskVersion", summary.getOndiskVersion());
      // Output the version of OIV (which is not necessarily the version of
      // the fsimage file).  This could be helpful in the case where a bug
      // in OIV leads to information loss in the XML-- we can quickly tell
      // if a specific fsimage XML file is affected by this bug.
      o("oivRevision", VersionInfo.getRevision());
      out.print("</version>\n");

      ArrayList<FileSummary.Section> sections = Lists.newArrayList(summary
          .getSectionsList());
      Collections.sort(sections, new Comparator<FileSummary.Section>() {
        @Override
        public int compare(FileSummary.Section s1, FileSummary.Section s2) {
          SectionName n1 = SectionName.fromString(s1.getName());
          SectionName n2 = SectionName.fromString(s2.getName());
          if (n1 == null) {
            return n2 == null ? 0 : -1;
          } else if (n2 == null) {
            return -1;
          } else {
            return n1.ordinal() - n2.ordinal();
          }
        }
      });

      for (FileSummary.Section s : sections) {
        fin.getChannel().position(s.getOffset());
        InputStream is = FSImageUtil.wrapInputStreamForCompression(conf,
            summary.getCodec(), new BufferedInputStream(new LimitInputStream(
                fin, s.getLength())));

        switch (SectionName.fromString(s.getName())) {
        case NS_INFO:
          dumpNameSection(is);
          break;
        case STRING_TABLE:
          loadStringTable(is);
          break;
        case INODE:
          dumpINodeSection(is);
          break;
        case INODE_REFERENCE:
          dumpINodeReferenceSection(is);
          break;
        case INODE_DIR:
          dumpINodeDirectorySection(is);
          break;
        case FILES_UNDERCONSTRUCTION:
          dumpFileUnderConstructionSection(is);
          break;
        case SNAPSHOT:
          dumpSnapshotSection(is);
          break;
        case SNAPSHOT_DIFF:
          dumpSnapshotDiffSection(is);
          break;
        case SECRET_MANAGER:
          dumpSecretManagerSection(is);
          break;
        case CACHE_MANAGER:
          dumpCacheManagerSection(is);
          break;
        default:
          break;
        }
      }
      out.print("</fsimage>\n");
    }
  }

  private void dumpCacheManagerSection(InputStream is) throws IOException {
    out.print("<CacheManagerSection>");
    CacheManagerSection s = CacheManagerSection.parseDelimitedFrom(is);
    o("nextDirectiveId", s.getNextDirectiveId());
    o("numDirectives", s.getNumDirectives());
    o("numPools", s.getNumPools());
    for (int i = 0; i < s.getNumPools(); ++i) {
      CachePoolInfoProto p = CachePoolInfoProto.parseDelimitedFrom(is);
      out.print("<pool>");
      o("poolName", p.getPoolName()).o("ownerName", p.getOwnerName())
          .o("groupName", p.getGroupName()).o("mode", p.getMode())
          .o("limit", p.getLimit())
          .o("maxRelativeExpiry", p.getMaxRelativeExpiry());
      out.print("</pool>\n");
    }
    for (int i = 0; i < s.getNumDirectives(); ++i) {
      CacheDirectiveInfoProto p = CacheDirectiveInfoProto
          .parseDelimitedFrom(is);
      out.print("<directive>");
      o("id", p.getId()).o("path", p.getPath())
          .o("replication", p.getReplication()).o("pool", p.getPool());
      out.print("<expiration>");
      CacheDirectiveInfoExpirationProto e = p.getExpiration();
      o("millis", e.getMillis()).o("relative", e.getIsRelative());
      out.print("</expiration>\n");
      out.print("</directive>\n");
    }
    out.print("</CacheManagerSection>\n");

  }

  private void dumpFileUnderConstructionSection(InputStream in)
      throws IOException {
    out.print("<FileUnderConstructionSection>");
    while (true) {
      FileUnderConstructionEntry e = FileUnderConstructionEntry
          .parseDelimitedFrom(in);
      if (e == null) {
        break;
      }
      out.print("<inode>");
      o("id", e.getInodeId()).o("path", e.getFullPath());
      out.print("</inode>\n");
    }
    out.print("</FileUnderConstructionSection>\n");
  }

  private void dumpXattrs(INodeSection.XAttrFeatureProto xattrs) {
    out.print("<xattrs>");
    for (INodeSection.XAttrCompactProto xattr : xattrs.getXAttrsList()) {
      out.print("<xattr>");
      int encodedName = xattr.getName();
      int ns = (XATTR_NAMESPACE_MASK & (encodedName >> XATTR_NAMESPACE_OFFSET)) |
          ((XATTR_NAMESPACE_EXT_MASK & (encodedName >> XATTR_NAMESPACE_EXT_OFFSET)) << 2);
      o("ns", XAttrProtos.XAttrProto.
          XAttrNamespaceProto.valueOf(ns).toString());
      o("name", stringTable[XATTR_NAME_MASK & (encodedName >> XATTR_NAME_OFFSET)]);
      ByteString val = xattr.getValue();
      if (val.isValidUtf8()) {
        o("val", val.toStringUtf8());
      } else {
        o("valHex", Hex.encodeHexString(val.toByteArray()));
      }
      out.print("</xattr>");
    }
    out.print("</xattrs>");
  }

  private void dumpINodeDirectory(INodeDirectory d) {
    o("mtime", d.getModificationTime()).o("permission",
        dumpPermission(d.getPermission()));
    if (d.hasXAttrs()) {
      dumpXattrs(d.getXAttrs());
    }
    dumpAcls(d.getAcl());
    if (d.hasDsQuota() && d.hasNsQuota()) {
      o("nsquota", d.getNsQuota()).o("dsquota", d.getDsQuota());
    }
    INodeSection.QuotaByStorageTypeFeatureProto typeQuotas =
      d.getTypeQuotas();
    if (typeQuotas != null) {
      for (INodeSection.QuotaByStorageTypeEntryProto entry:
            typeQuotas.getQuotasList()) {
        out.print("<typeQuota>");
        o("type", entry.getStorageType().toString());
        o("quota", entry.getQuota());
        out.print("</typeQuota>");
      }
    }
  }

  private void dumpINodeDirectorySection(InputStream in) throws IOException {
    out.print("<INodeDirectorySection>");
    while (true) {
      INodeDirectorySection.DirEntry e = INodeDirectorySection.DirEntry
          .parseDelimitedFrom(in);
      // note that in is a LimitedInputStream
      if (e == null) {
        break;
      }
      out.print("<directory>");
      o("parent", e.getParent());
      for (long id : e.getChildrenList()) {
        o("child", id);
      }
      for (int refId : e.getRefChildrenList()) {
        o("refChild", refId);
      }
      out.print("</directory>\n");
    }
    out.print("</INodeDirectorySection>\n");
  }

  private void dumpINodeReferenceSection(InputStream in) throws IOException {
    out.print("<INodeReferenceSection>");
    while (true) {
      INodeReferenceSection.INodeReference e = INodeReferenceSection
          .INodeReference.parseDelimitedFrom(in);
      if (e == null) {
        break;
      }
      dumpINodeReference(e);
    }
    out.print("</INodeReferenceSection>");
  }

  private void dumpINodeReference(INodeReferenceSection.INodeReference r) {
    out.print("<ref>");
    o("referredId", r.getReferredId()).o("name", r.getName().toStringUtf8())
        .o("dstSnapshotId", r.getDstSnapshotId())
        .o("lastSnapshotId", r.getLastSnapshotId());
    out.print("</ref>\n");
  }

  private void dumpINodeFile(INodeSection.INodeFile f) {
    o("replication", f.getReplication()).o("mtime", f.getModificationTime())
        .o("atime", f.getAccessTime())
        .o("preferredBlockSize", f.getPreferredBlockSize())
        .o("permission", dumpPermission(f.getPermission()));
    if (f.hasXAttrs()) {
      dumpXattrs(f.getXAttrs());
    }
    dumpAcls(f.getAcl());
    if (f.getBlocksCount() > 0) {
      out.print("<blocks>");
      for (BlockProto b : f.getBlocksList()) {
        out.print("<block>");
        o("id", b.getBlockId()).o("genstamp", b.getGenStamp()).o("numBytes",
            b.getNumBytes());
        out.print("</block>\n");
      }
      out.print("</blocks>\n");
    }
    if (f.hasStoragePolicyID()) {
      o("storagePolicyId", f.getStoragePolicyID());
    }
    if (f.getIsStriped()) {
      out.print("<isStriped/>");
    }

    if (f.hasFileUC()) {
      INodeSection.FileUnderConstructionFeature u = f.getFileUC();
      out.print("<file-under-construction>");
      o("clientName", u.getClientName()).o("clientMachine",
          u.getClientMachine());
      out.print("</file-under-construction>\n");
    }
  }

  private void dumpAcls(AclFeatureProto aclFeatureProto) {
    ImmutableList<AclEntry> aclEntryList = FSImageFormatPBINode.Loader
        .loadAclEntries(aclFeatureProto, stringTable);
    if (aclEntryList.size() > 0) {
      out.print("<acls>");
      for (AclEntry aclEntry : aclEntryList) {
        o("acl", aclEntry.toString());
      }
      out.print("</acls>");
    }
  }

  private void dumpINodeSection(InputStream in) throws IOException {
    INodeSection s = INodeSection.parseDelimitedFrom(in);
    out.print("<INodeSection>");
    o("lastInodeId", s.getLastInodeId());
    o("numInodes", s.getNumInodes());
    for (int i = 0; i < s.getNumInodes(); ++i) {
      INodeSection.INode p = INodeSection.INode.parseDelimitedFrom(in);
      out.print("<inode>");
      dumpINodeFields(p);
      out.print("</inode>\n");
    }
    out.print("</INodeSection>\n");
  }

  private void dumpINodeFields(INodeSection.INode p) {
    o("id", p.getId()).o("type", p.getType()).o("name",
        p.getName().toStringUtf8());
    if (p.hasFile()) {
      dumpINodeFile(p.getFile());
    } else if (p.hasDirectory()) {
      dumpINodeDirectory(p.getDirectory());
    } else if (p.hasSymlink()) {
      dumpINodeSymlink(p.getSymlink());
    }
  }

  private void dumpINodeSymlink(INodeSymlink s) {
    o("permission", dumpPermission(s.getPermission()))
        .o("target", s.getTarget().toStringUtf8())
        .o("mtime", s.getModificationTime()).o("atime", s.getAccessTime());
  }

  private void dumpNameSection(InputStream in) throws IOException {
    NameSystemSection s = NameSystemSection.parseDelimitedFrom(in);
    out.print("<NameSection>");
    o("namespaceId", s.getNamespaceId());
    o("genstampV1", s.getGenstampV1()).o("genstampV2", s.getGenstampV2())
        .o("genstampV1Limit", s.getGenstampV1Limit())
        .o("lastAllocatedBlockId", s.getLastAllocatedBlockId())
        .o("txid", s.getTransactionId());
    out.print("</NameSection>\n");
  }

  private String dumpPermission(long permission) {
    PermissionStatus permStatus = FSImageFormatPBINode.Loader.
        loadPermission(permission, stringTable);
    return String.format("%s:%s:%04o", permStatus.getUserName(),
        permStatus.getGroupName(), permStatus.getPermission().toExtendedShort());
  }

  private void dumpSecretManagerSection(InputStream is) throws IOException {
    out.print("<SecretManagerSection>");
    SecretManagerSection s = SecretManagerSection.parseDelimitedFrom(is);
    int expectedNumDelegationKeys = s.getNumKeys();
    int expectedNumTokens = s.getNumTokens();
    o("currentId", s.getCurrentId()).o("tokenSequenceNumber",
        s.getTokenSequenceNumber()).
        o("numDelegationKeys", expectedNumDelegationKeys).
        o("numTokens", expectedNumTokens);
    for (int i = 0; i < expectedNumDelegationKeys; i++) {
      SecretManagerSection.DelegationKey dkey =
          SecretManagerSection.DelegationKey.parseDelimitedFrom(is);
      out.print("<delegationKey>");
      o("id", dkey.getId());
      o("key", Hex.encodeHexString(dkey.getKey().toByteArray()));
      if (dkey.hasExpiryDate()) {
        dumpDate("expiry", dkey.getExpiryDate());
      }
      out.print("</delegationKey>");
    }
    for (int i = 0; i < expectedNumTokens; i++) {
      SecretManagerSection.PersistToken token =
          SecretManagerSection.PersistToken.parseDelimitedFrom(is);
      out.print("<token>");
      if (token.hasVersion()) {
        o("version", token.getVersion());
      }
      if (token.hasOwner()) {
        o("owner", token.getOwner());
      }
      if (token.hasRenewer()) {
        o("renewer", token.getRenewer());
      }
      if (token.hasRealUser()) {
        o("realUser", token.getRealUser());
      }
      if (token.hasIssueDate()) {
        dumpDate("issueDate", token.getIssueDate());
      }
      if (token.hasMaxDate()) {
        dumpDate("maxDate", token.getMaxDate());
      }
      if (token.hasSequenceNumber()) {
        o("sequenceNumber", token.getSequenceNumber());
      }
      if (token.hasMasterKeyId()) {
        o("masterKeyId", token.getMasterKeyId());
      }
      if (token.hasExpiryDate()) {
        dumpDate("expiryDate", token.getExpiryDate());
      }
      out.print("</token>");
    }
    out.print("</SecretManagerSection>");
  }

  private void dumpDate(String tag, long date) {
    out.print("<" + tag + ">" +
      isoDateFormat.format(new Date(date)) + "</" + tag + ">");
  }

  private void dumpSnapshotDiffSection(InputStream in) throws IOException {
    out.print("<SnapshotDiffSection>");
    while (true) {
      SnapshotDiffSection.DiffEntry e = SnapshotDiffSection.DiffEntry
          .parseDelimitedFrom(in);
      if (e == null) {
        break;
      }
      switch (e.getType()) {
      case FILEDIFF:
        out.print("<fileDiffEntry>");
        break;
      case DIRECTORYDIFF:
        out.print("<dirDiffEntry>");
        break;
      default:
        throw new IOException("unknown DiffEntry type " + e.getType());
      }
      o("inodeId", e.getInodeId());
      o("count", e.getNumOfDiff());
      switch (e.getType()) {
      case FILEDIFF: {
        for (int i = 0; i < e.getNumOfDiff(); ++i) {
          out.print("<fileDiff>");
          SnapshotDiffSection.FileDiff f = SnapshotDiffSection.FileDiff
              .parseDelimitedFrom(in);
          o("snapshotId", f.getSnapshotId()).o("size", f.getFileSize()).o(
              "name", f.getName().toStringUtf8());
          out.print("</fileDiff>\n");
        }
      }
        break;
      case DIRECTORYDIFF: {
        for (int i = 0; i < e.getNumOfDiff(); ++i) {
          out.print("<dirDiff>");
          SnapshotDiffSection.DirectoryDiff d = SnapshotDiffSection.DirectoryDiff
              .parseDelimitedFrom(in);
          o("snapshotId", d.getSnapshotId())
              .o("childrenSize", d.getChildrenSize())
              .o("isSnapshotRoot", d.getIsSnapshotRoot())
              .o("name", d.getName().toStringUtf8())
              .o("createdListSize", d.getCreatedListSize());
          for (long did : d.getDeletedINodeList()) {
            o("deletedInode", did);
          }
          for (int dRefid : d.getDeletedINodeRefList()) {
            o("deletedInoderef", dRefid);
          }
          for (int j = 0; j < d.getCreatedListSize(); ++j) {
            SnapshotDiffSection.CreatedListEntry ce = SnapshotDiffSection.CreatedListEntry
                .parseDelimitedFrom(in);
            out.print("<created>");
            o("name", ce.getName().toStringUtf8());
            out.print("</created>\n");
          }
          out.print("</dirDiff>\n");
        }
        break;
      }
      default:
        break;
      }
      switch (e.getType()) {
      case FILEDIFF:
        out.print("</fileDiffEntry>");
        break;
      case DIRECTORYDIFF:
        out.print("</dirDiffEntry>");
        break;
      default:
        throw new IOException("unknown DiffEntry type " + e.getType());
      }
    }
    out.print("</SnapshotDiffSection>\n");
  }

  private void dumpSnapshotSection(InputStream in) throws IOException {
    out.print("<SnapshotSection>");
    SnapshotSection s = SnapshotSection.parseDelimitedFrom(in);
    o("snapshotCounter", s.getSnapshotCounter());
    o("numSnapshots", s.getNumSnapshots());
    if (s.getSnapshottableDirCount() > 0) {
      out.print("<snapshottableDir>");
      for (long id : s.getSnapshottableDirList()) {
        o("dir", id);
      }
      out.print("</snapshottableDir>\n");
    }
    for (int i = 0; i < s.getNumSnapshots(); ++i) {
      SnapshotSection.Snapshot pbs = SnapshotSection.Snapshot
          .parseDelimitedFrom(in);
      out.print("<snapshot>");
      o("id", pbs.getSnapshotId());
      out.print("<root>");
      dumpINodeFields(pbs.getRoot());
      out.print("</root>");
      out.print("</snapshot>");
    }
    out.print("</SnapshotSection>\n");
  }

  private void loadStringTable(InputStream in) throws IOException {
    StringTableSection s = StringTableSection.parseDelimitedFrom(in);
    stringTable = new String[s.getNumEntry() + 1];
    for (int i = 0; i < s.getNumEntry(); ++i) {
      StringTableSection.Entry e = StringTableSection.Entry
          .parseDelimitedFrom(in);
      stringTable[e.getId()] = e.getStr();
    }
  }

  private PBImageXmlWriter o(final String e, final Object v) {
    if (v instanceof Boolean) {
      // For booleans, the presence of the element indicates true, and its
      // absence indicates false.
      if ((Boolean)v != false) {
        out.print("<" + e + "/>");
      }
      return this;
    }
    out.print("<" + e + ">" +
        XMLUtils.mangleXmlString(v.toString(), true) + "</" + e + ">");
    return this;
  }
}
