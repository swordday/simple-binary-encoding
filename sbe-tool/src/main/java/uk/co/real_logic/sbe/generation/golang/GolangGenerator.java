/*
 * Copyright (C) 2016 MarketFactory, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.sbe.generation.golang;

import uk.co.real_logic.sbe.PrimitiveType;
import uk.co.real_logic.sbe.generation.CodeGenerator;
import org.agrona.generation.OutputManager;
import uk.co.real_logic.sbe.ir.*;
import org.agrona.Verify;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static uk.co.real_logic.sbe.PrimitiveType.CHAR;
import static uk.co.real_logic.sbe.generation.golang.GolangUtil.*;
import static uk.co.real_logic.sbe.ir.GenerationUtil.collectVarData;
import static uk.co.real_logic.sbe.ir.GenerationUtil.collectGroups;
import static uk.co.real_logic.sbe.ir.GenerationUtil.collectFields;

public class GolangGenerator implements CodeGenerator
{
    private final Ir ir;
    private final OutputManager outputManager;

    private TreeSet<String> imports;

    public GolangGenerator(final Ir ir, final OutputManager outputManager)
        throws IOException
    {
        Verify.notNull(ir, "ir");
        Verify.notNull(outputManager, "outputManager");

        this.ir = ir;
        this.outputManager = outputManager;
    }

    public void generateMessageHeaderStub() throws IOException
    {
        final String messageHeader = "MessageHeader";
        try (Writer out = outputManager.createOutput(messageHeader))
        {
            // Initialize the imports
            imports = new TreeSet<>();
            imports.add("io");
            imports.add("encoding/binary");

            final StringBuilder sb = new StringBuilder();

            final List<Token> tokens = ir.headerStructure().tokens();

            generateTypeDeclaration(sb, messageHeader);
            generateTypeBodyComposite(sb, messageHeader, tokens.subList(1, tokens.size() - 1));
            generateEncodeDecode(sb, messageHeader, tokens.subList(1, tokens.size() - 1), false, false);
            generateEncodedLength(sb, messageHeader, tokens.get(0).encodedLength());
            generateCompositePropertyElements(sb, messageHeader, tokens.subList(1, tokens.size() - 1));

            out.append(generateFileHeader(ir.namespaces(), messageHeader));
            out.append(sb);
        }
    }

    public void generateTypeStubs() throws IOException
    {
        for (final List<Token> tokens : ir.types())
        {
            switch (tokens.get(0).signal())
            {
                case BEGIN_ENUM:
                    generateEnum(tokens);
                    break;

                case BEGIN_SET:
                    generateChoiceSet(tokens);
                    break;

                case BEGIN_COMPOSITE:
                    generateComposite(tokens, "");
                    break;

                case BEGIN_MESSAGE:
                    break;

                default:
                    break;
            }
        }
    }

    public void generate() throws IOException
    {
        generateMessageHeaderStub();
        generateTypeStubs();

        for (final List<Token> tokens : ir.messages())
        {
            final Token msgToken = tokens.get(0);
            final String typeName = formatTypeName(msgToken.name());

            try (Writer out = outputManager.createOutput(typeName))
            {
                final StringBuilder sb = new StringBuilder();

                // Initialize the imports
                imports = new TreeSet<>();
                this.imports.add("io");
                this.imports.add("encoding/binary");

                generateTypeDeclaration(sb, typeName);
                generateTypeBody(sb, typeName, tokens.subList(1, tokens.size() - 1));

                generateMessageCode(sb, typeName, tokens);

                final List<Token> messageBody = tokens.subList(1, tokens.size() - 1);
                int i = 0;

                final List<Token> fields = new ArrayList<>();
                i = collectFields(messageBody, i, fields);

                final List<Token> groups = new ArrayList<>();
                i = collectGroups(messageBody, i, groups);

                final List<Token> varData = new ArrayList<>();
                collectVarData(messageBody, i, varData);

                generateFields(sb, typeName, fields, "");
                generateGroups(sb, groups, typeName);
                generateGroupProperties(sb, groups, typeName);
                generateVarData(sb, typeName, varData, "");

                out.append(generateFileHeader(ir.namespaces(), typeName));
                out.append(sb);

            }
        }
    }

    private String generateEncodeOffset(final int gap, final String indent)
    {
        if (gap > 0)
        {
            return String.format(
                "\n" +
                "%1$s\tfor i := 0; i < %2$d; i++ {\n" +
                "%1$s\t\tif err := binary.Write(writer, order, uint8(0)); err != nil {\n" +
                "%1$s\t\t\treturn err\n" +
                "%1$s\t\t}\n" +
                "%1$s\t}\n",
                indent,
                gap);
        }
        return "";
    }

    private String generateDecodeOffset(final int gap, final String indent)
    {
        if (gap > 0)
        {
            this.imports.add("io");
            this.imports.add("io/ioutil");
            return String.format("%1$s\tio.CopyN(ioutil.Discard, reader, %2$d)\n", indent, gap);
        }
        return "";
    }

    private void generateCharacterEncodingRangeCheck(
        final StringBuilder sb,
        final String varName,
        final Token token)
    {
        switch (token.encoding().characterEncoding())
        {
            case "ASCII":
                this.imports.add("fmt");
                sb.append(String.format(
                    "\tfor idx, ch := range %1$s {\n" +
                    "\t\tif ch > 127 {\n" +
                    "\t\t\treturn fmt.Errorf(\"%1$s[%%d]=%%d" +
                    " failed ASCII validation\", idx, ch)\n" +
                    "\t\t}\n" +
                    "\t}\n",
                    varName));
                break;
            case "UTF-8":
                this.imports.add("errors");
                this.imports.add("unicode/utf8");
                sb.append(String.format(
                    "\tif !utf8.Valid(%1$s[:]) {\n" +
                    "\t\treturn errors.New(\"%1$s failed UTF-8 validation\")\n" +
                    "\t}\n",
                    varName));
                break;
        }
    }

    private void generateEncodePrimitive(
        final StringBuilder sb,
        final char varName,
        final String propertyName)
    {
        sb.append(String.format(
            "\tif err := binary.Write(writer, order, %1$s.%2$s); err != nil {\n" +
            "\t\treturn err\n" +
            "\t}\n",
            varName,
            propertyName));
    }

    private void generateDecodePrimitive(
        final StringBuilder sb,
        final String varName,
        final Token token)
    {
        this.imports.add("fmt");

        // Decode of a constant is simply assignment
        if (token.isConstantEncoding())
        {
            // if primitiveType="char" this is a character array
            if (token.encoding().primitiveType() == CHAR)
            {
                if (token.encoding().constValue().size() > 1)
                {
                    // constValue is a string
                    sb.append(String.format(
                        "\tcopy(%1$s[:], \"%2$s\")\n",
                        varName,
                        token.encoding().constValue()));
                }
                else
                {
                    // constValue is a char
                    sb.append(String.format(
                        "\t%1$s[0] = %2$s\n",
                        varName,
                        token.encoding().constValue()));
                }
            }
            else
            {
                sb.append(String.format(
                    "\t%1$s = %2$s\n",
                    varName,
                    generateLiteral(token.encoding().primitiveType(), token.encoding().constValue().toString())));
            }

        }
        else
        {
            final String copyNull;
            if (token.arrayLength() > 1)
            {
                copyNull =
                    "\t\tfor idx := 0; idx < %2$s; idx++ {\n" +
                    "\t\t\t%1$s[idx] = %1$sNullValue()\n" +
                    "\t\t}\n";
            }
            else
            {
                copyNull =
                    "\t\t%1$s = %1$sNullValue()\n";
            }

            sb.append(String.format(
                "\tif !%1$sInActingVersion(actingVersion) {\n" +
                copyNull +
                "\t} else {\n" +
                "\t\tif err := binary.Read(reader, order, &%1$s); err != nil {\n" +
                "\t\t\treturn err\n" +
                "\t\t}\n" +
                "\t}\n",
                varName,
                token.arrayLength()));
        }
    }

    private void generateRangeCheckPrimitive(
        final StringBuilder sb,
        final String varName,
        final Token token)
    {
        // Constant values don't need checking
        if (token.isConstantEncoding())
        {
            return;
        }

        // If this field is unknown then we have nothing to check
        // Otherwise do a The Min,MaxValue checks (possibly for arrays)
        this.imports.add("fmt");
        if (token.arrayLength() > 1)
        {
            sb.append(String.format(
                "\tif %1$sInActingVersion(actingVersion) {\n" +
                "\t\tfor idx := 0; idx < %2$s; idx++ {\n" +
                "\t\t\tif %1$s[idx] < %1$sMinValue() || %1$s[idx] > %1$sMaxValue() {\n" +
                "\t\t\t\treturn fmt.Errorf(\"Range check failed on %1$s[%%d] " +
                "(%%d < %%d > %%d)\", idx, %1$sMinValue(), %1$s[idx], %1$sMaxValue())\n" +
                "\t\t\t}\n" +
                "\t\t}\n" +
                "\t}\n",
                varName,
                token.arrayLength()));
        }
        else
        {
            sb.append(String.format(
                "\tif %1$sInActingVersion(actingVersion) {\n" +
                "\t\tif %1$s < %1$sMinValue() || %1$s > %1$sMaxValue() {\n" +
                "\t\t\treturn fmt.Errorf(\"Range check failed on %1$s " +
                "(%%d < %%d > %%d)\", %1$sMinValue(), %1$s, %1$sMaxValue())\n" +
                "\t\t}\n" +
                "\t}\n",
                varName));

        }

        // Fields that are an [n]byte may have a characterEncoding which
        // should also be checked
        if (token.arrayLength() > 1 && token.encoding().primitiveType() == PrimitiveType.CHAR)
        {
            generateCharacterEncodingRangeCheck(sb, varName, token);
        }
    }

    private void generateInitPrimitive(
        final StringBuilder sb,
        final String varName,
        final Token token)
    {
        // Decode of a constant is simply assignment
        if (token.isConstantEncoding())
        {
            // if primitiveType="char" this is a character array
            if (token.encoding().primitiveType() == CHAR)
            {
                if (token.encoding().constValue().size() > 1)
                {
                    // constValue is a string
                    sb.append(String.format(
                        "\tcopy(%1$s[:], \"%2$s\")\n",
                        varName,
                        token.encoding().constValue()));
                }
                else
                {
                    // constValue is a char
                    sb.append(String.format(
                        "\t%1$s[0] = %2$s\n",
                        varName,
                        token.encoding().constValue()));
                }
            }
            else
            {
                sb.append(String.format(
                    "\t%1$s = %2$s\n",
                    varName,
                    generateLiteral(token.encoding().primitiveType(), token.encoding().constValue().toString())));
            }
        }
    }

    private void generateEncodeDecodeOpen(
        final StringBuilder encode,
        final StringBuilder decode,
        final StringBuilder rangecheck,
        final StringBuilder init,
        final char varName,
        final String typeName,
        final Boolean isMessage,
        final Boolean isExtensible)
    {
        generateEncodeHeader(encode, varName, typeName, isMessage);
        generateDecodeHeader(decode, varName, typeName, isMessage, isExtensible);
        generateRangeCheckHeader(rangecheck, varName, typeName);
        generateInitHeader(init, varName, typeName);
    }

    private void generateEncodeDecodeClose(
        final StringBuilder encode,
        final StringBuilder decode,
        final StringBuilder rangecheck,
        final StringBuilder init)
    {
        encode.append("\treturn nil\n}\n");
        decode.append("\treturn nil\n}\n");
        rangecheck.append("\treturn nil\n}\n");
        init.append("\treturn\n}\n");
    }


    // Newer messages and groups can add extra properties before the variable
    // length elements (groups and vardata). We read past the difference
    // between the message's blockLength and our (older) schema's blockLength
    private void generateExtensionCheck(
        StringBuilder sb,
        char varName)
    {
        this.imports.add("io");
        this.imports.add("io/ioutil");
        sb.append(String.format(
            "\tif actingVersion > %1$s.SbeSchemaVersion() && blockLength > %1$s.SbeBlockLength() {\n" +
            "\t\tio.CopyN(ioutil.Discard, reader, int64(blockLength-%1$s.SbeBlockLength()))\n" +
            "\t}\n",
            varName));
    }

    // Returns the size of the last Message/Group
    private int generateEncodeDecode(
        final StringBuilder sb,
        final String typeName,
        final List<Token> tokens,
        boolean isMessage,
        boolean isExtensible)
    {
        final char varName = Character.toLowerCase(typeName.charAt(0));
        final StringBuilder encode = new StringBuilder();
        final StringBuilder decode = new StringBuilder();
        final StringBuilder init = new StringBuilder();
        final StringBuilder rangecheck = new StringBuilder();
        final StringBuilder nested = new StringBuilder();
        int currentOffset = 0;
        int gap = 0;
        boolean extensionStarted = false;

        // Open all our methods
        generateEncodeDecodeOpen(encode, decode, rangecheck, init, varName, typeName, isMessage, isExtensible);

        for (int i = 0; i < tokens.size(); i++)
        {
            final Token signalToken = tokens.get(i);
            final String propertyName = formatPropertyName(signalToken.name());

            switch (signalToken.signal())
            {
                case BEGIN_MESSAGE: // Check range *before* we encode setting the acting version to schema version
                    encode.append(String.format(
                        "\tif doRangeCheck {\n" +
                        "\t\tif err := %1$s.RangeCheck(%1$s.SbeSchemaVersion(), %1$s.SbeSchemaVersion()); err != nil {\n" +
                        "\t\t\treturn err\n" +
                        "\t\t}\n" +
                        "\t}\n",
                        varName));
                    break;

                case END_MESSAGE:
                    // Newer version extra fields check
                    if (isExtensible && !extensionStarted)
                    {
                        generateExtensionCheck(decode, varName);
                        extensionStarted = true;
                    }

                    // Check range *after* we decode using the acting
                    // version of the encoded message
                    decode.append(String.format(
                        "\tif doRangeCheck {\n" +
                        "\t\tif err := %1$s.RangeCheck(actingVersion, %1$s.SbeSchemaVersion()); err != nil {\n" +
                        "\t\t\treturn err\n" +
                        "\t\t}\n" +
                        "\t}\n",
                        varName));
                    break;

                case BEGIN_ENUM:
                case BEGIN_SET:
                    currentOffset += generatePropertyEncodeDecode(
                        signalToken, typeName, encode, decode, currentOffset);
                    i += signalToken.componentTokenCount() - 1;
                    break;

                case BEGIN_COMPOSITE:
                    currentOffset += generatePropertyEncodeDecode(
                        signalToken, typeName, encode, decode, currentOffset);
                    i += signalToken.componentTokenCount() - 1;

                    rangecheck.append(String.format(
                        "\tif err := %1$s.%2$s.RangeCheck(actingVersion, schemaVersion); err != nil {\n" +
                        "\t\treturn err\n" +
                        "\t}\n",
                        varName, propertyName));
                    break;

                case BEGIN_FIELD:
                    if (tokens.size() >= i + 1)
                    {
                        currentOffset += generateFieldEncodeDecode(
                            tokens.subList(i, tokens.size() - 1), varName, currentOffset, encode, decode, rangecheck, init);

                        // Encodings just move past the encoding token
                        if (tokens.get(i + 1).signal() == Signal.ENCODING)
                        {
                            i += 1;
                        }
                        else
                        {
                            i += signalToken.componentTokenCount() - 1;
                        }
                    }
                    break;

                case ENCODING:
                    gap = signalToken.offset() - currentOffset;
                    encode.append(generateEncodeOffset(gap, ""));
                    decode.append(generateDecodeOffset(gap, ""));
                    currentOffset += signalToken.encodedLength() + gap;

                    // Encode of a constant is a nullop
                    if (!signalToken.isConstantEncoding())
                    {
                        generateEncodePrimitive(encode, varName, formatPropertyName(signalToken.name()));
                    }
                    final String primitive = Character.toString(varName) + "." + propertyName;
                    generateDecodePrimitive(decode, primitive, signalToken);
                    generateRangeCheckPrimitive(rangecheck, primitive, signalToken);
                    generateInitPrimitive(init, primitive, signalToken);
                    break;

                case BEGIN_GROUP:
                    // Newer version extra fields check
                    if (isExtensible && !extensionStarted)
                    {
                        generateExtensionCheck(decode, varName);
                        extensionStarted = true;
                    }

                    // Write the group, saving any extra offset we need to skip
                    currentOffset += generateGroupEncodeDecode(
                        tokens.subList(i, tokens.size() - 1),
                        typeName,
                        encode, decode, rangecheck, currentOffset);

                    // Recurse
                    gap = Math.max(0,
                        signalToken.encodedLength() -
                        generateEncodeDecode(
                            nested,
                            typeName + toUpperFirstChar(signalToken.name()),
                            tokens.subList(i + 5, tokens.size() - 1),
                            false, true));

                    // Group gap blocklength handling
                    encode.append(generateEncodeOffset(gap, "\t") + "\t}\n");
                    decode.append(generateDecodeOffset(gap, "\t") + "\t}\n");

                    // And we can move over this group to the END_GROUP
                    i += signalToken.componentTokenCount() - 1;

                    break;

                case END_GROUP:
                    // Newer version extra fields check
                    if (isExtensible && !extensionStarted)
                    {
                        generateExtensionCheck(decode, varName);
                        extensionStarted = true;
                    }
                    // Close out this group and unwind
                    generateEncodeDecodeClose(encode, decode, rangecheck, init);
                    sb.append(encode).append(decode).append(rangecheck).append(init).append(nested);
                    return currentOffset; // for gap calculations

                case BEGIN_VAR_DATA:
                    // Newer version extra fields check
                    if (isExtensible && !extensionStarted)
                    {
                        generateExtensionCheck(decode, varName);
                        extensionStarted = true;
                    }
                    currentOffset += generateVarDataEncodeDecode(
                        tokens.subList(i, tokens.size() - 1),
                        typeName,
                        encode, decode, rangecheck, currentOffset);
                    // And we can move over this group
                    i += signalToken.componentTokenCount() - 1;
                    break;
            }
        }
        // You can use blockLength on both messages and groups (handled above)
        // to leave some space (akin to an offset).
        final Token endToken = tokens.get(tokens.size() - 1);
        if (endToken.signal() == Signal.END_MESSAGE)
        {
            gap = endToken.encodedLength() - currentOffset;
            encode.append(generateEncodeOffset(gap, ""));
            decode.append(generateDecodeOffset(gap, ""));
        }
        // Close out the methods and append
        generateEncodeDecodeClose(encode, decode, rangecheck, init);
        sb.append(encode).append(decode).append(rangecheck).append(init).append(nested);
        return currentOffset;
    }

    private void generateEnumEncodeDecode(
        final StringBuilder sb,
        final String enumName,
        final Token token)
    {
        final char varName = Character.toLowerCase(enumName.charAt(0));

        // Encode
        generateEncodeHeader(sb, varName, enumName + "Enum", false);
        sb.append(String.format(
            "\tif err := binary.Write(writer, order, %1$s); err != nil {\n" +
            "\t\treturn err\n" +
            "\t}\n" +
            "\treturn nil\n}\n",
            varName));

        // Decode
        generateDecodeHeader(sb, varName, enumName + "Enum", false, false);
        sb.append(String.format(
            "\tif err := binary.Read(reader, order, %1$s); err != nil {\n" +
            "\t\treturn err\n" +
            "\t}\n" +
            "\treturn nil\n}\n",
            varName));

        // Range check
        // We use golang's reflect to range over the values in the
        // struct to check which are legitimate
        imports.add("fmt");
        imports.add("reflect");
        generateRangeCheckHeader(sb, varName, enumName + "Enum");

        // For enums we can add new fields so if we're decoding a
        // newer version then the content is definitionally ok.
        // When encoding actingVersion === schemaVersion
        sb.append(
            "\tif actingVersion > schemaVersion {\n" +
            "\t\treturn nil\n" +
            "\t}\n");

        // Otherwise the value should be known
        sb.append(String.format(
            "\tvalue := reflect.ValueOf(%2$s)\n" +
            "\tfor idx := 0; idx < value.NumField(); idx++ {\n" +
            "\t\tif %1$s == value.Field(idx).Interface() {\n" +
            "\t\t\treturn nil\n" +
            "\t\t}\n" +
            "\t}\n" +
            "\treturn fmt.Errorf(\"Range check failed on %2$s, unknown enumeration value %%d\", %1$s)\n" +
            "}\n",
            varName,
            enumName));
    }

    private void generateChoiceEncodeDecode(
        final StringBuilder sb,
        final String choiceName,
        final Token token)
    {
        final char varName = Character.toLowerCase(choiceName.charAt(0));

        // Encode
        generateEncodeHeader(sb, varName, choiceName, false);

        sb.append(String.format(
            "\tvar wireval uint%1$d = 0\n" +
            "\tfor k, v := range %2$s {\n" +
            "\t\tif v {\n" +
            "\t\t\twireval |= (1 << uint(k))\n" +
            "\t\t}\n\t}\n" +
            "\treturn binary.Write(writer, order, wireval)\n" +
            "}\n",
            token.encodedLength() * 8,
            varName));

        // Decode
        generateDecodeHeader(sb, varName, choiceName, false, false);

        sb.append(String.format(
            "\tvar wireval uint%1$d\n\n" +
            "\tif err := binary.Read(reader, order, &wireval); err != nil {\n" +
            "\t\treturn err\n" +
            "\t}\n" +
            "\n" +
            "\tvar idx uint\n" +
            "\tfor idx = 0; idx < %1$d; idx++ {\n" +
            "\t\t%2$s[idx] = (wireval & (1 << idx)) > 0\n" +
            "\t}\n",
            token.encodedLength() * 8,
            varName));

        sb.append("\treturn nil\n}\n");
    }

    private void generateEncodeHeader(
        final StringBuilder sb,
        final char varName,
        final String typeName,
        final Boolean isMessage)
    {
        // Only messages get the rangeCheck flag
        String messageArgs = "";
        if (isMessage)
        {
            messageArgs = ", doRangeCheck bool";
        }

        sb.append(String.format(
            "\nfunc (%1$s %2$s) Encode(writer io.Writer, order binary.ByteOrder" +
            messageArgs +
            ") error {\n",
            varName,
            typeName));
    }

    private void generateDecodeHeader(
        final StringBuilder sb,
        final char varName,
        final String typeName,
        final Boolean isMessage,
        final Boolean isExtensible)
    {
        String decodeArgs = "";

        // Messages, groups, and vardata are extensible so need to know
        // working blocklength
        if (isExtensible)
        {
            decodeArgs += ", blockLength uint16";
        }

        // Only messages get the rangeCheck flags
        if (isMessage)
        {
            decodeArgs += ", doRangeCheck bool";
        }

        sb.append(String.format(
            "\nfunc (%1$s *%2$s) Decode(reader io.Reader, order binary.ByteOrder, actingVersion uint16" +
            decodeArgs +
            ") error {\n",
            varName,
            typeName));
    }

    private void generateRangeCheckHeader(
        final StringBuilder sb,
        final char varName,
        final String typeName)
    {
        sb.append(String.format(
            "\nfunc (%1$s %2$s) RangeCheck(actingVersion uint16, schemaVersion uint16) error {\n",
            varName,
            typeName));
    }

    private void generateInitHeader(
        final StringBuilder sb,
        final char varName,
        final String typeName)
    {
        // Init is a function rather than a method to guarantee uniqueness
        // as a field of a structure may collide
        sb.append(String.format(
            "\nfunc %1$sInit(%2$s *%1$s) {\n",
            typeName,
            varName));
    }

    // Returns how many extra tokens to skip over
    private int generateFieldEncodeDecode(
        final List<Token> tokens,
        final char varName,
        final int currentOffset,
        StringBuilder encode,
        StringBuilder decode,
        StringBuilder rc,
        StringBuilder init)
    {
        final Token signalToken = tokens.get(0);
        final Token encodingToken = tokens.get(1);
        final String propertyName = formatPropertyName(signalToken.name());

        final String golangType = golangTypeName(encodingToken.encoding().primitiveType());
        int gap = 0; // for offset calculations

        switch (encodingToken.signal())
        {
            case BEGIN_COMPOSITE:
            case BEGIN_ENUM:
            case BEGIN_SET:
                gap = signalToken.offset() - currentOffset;
                encode.append(generateEncodeOffset(gap, ""));
                decode.append(generateDecodeOffset(gap, ""));

                // Encode of a constant is a nullop, decode is assignment
                if (signalToken.isConstantEncoding())
                {
                    decode.append(String.format(
                        "\t%1$s.%2$s = %3$s\n",
                        varName, propertyName, signalToken.encoding().constValue()));
                    init.append(String.format(
                        "\t%1$s.%2$s = %3$s\n",
                        varName, propertyName, signalToken.encoding().constValue()));
                }
                else
                {
                    encode.append(String.format(
                        "\tif err := %1$s.%2$s.Encode(writer, order); err != nil {\n" +
                        "\t\treturn err\n" +
                        "\t}\n",
                        varName, propertyName));

                    decode.append(String.format(
                        "\tif %1$s.%2$sInActingVersion(actingVersion) {\n" +
                        "\t\tif err := %1$s.%2$s.Decode(reader, order, actingVersion); err != nil {\n" +
                        "\t\t\treturn err\n" +
                        "\t\t}\n" +
                        "\t}\n",
                        varName, propertyName));
                }

                if (encodingToken.signal() == Signal.BEGIN_ENUM)
                {
                    rc.append(String.format(
                        "\tif err := %1$s.%2$s.RangeCheck(actingVersion, schemaVersion); err != nil {\n" +
                        "\t\treturn err\n" +
                        "\t}\n",
                        varName, propertyName));
                }
                break;

            case ENCODING:
                gap = encodingToken.offset() - currentOffset;
                encode.append(generateEncodeOffset(gap, ""));
                decode.append(generateDecodeOffset(gap, ""));

                // Encode of a constant is a nullop
                if (!encodingToken.isConstantEncoding())
                {
                    generateEncodePrimitive(encode, varName, formatPropertyName(signalToken.name()));
                }
                final String primitive = Character.toString(varName) + "." + propertyName;
                generateDecodePrimitive(decode, primitive, encodingToken);
                generateRangeCheckPrimitive(rc, primitive, encodingToken);
                generateInitPrimitive(init, primitive, encodingToken);
                break;
        }

        return encodingToken.encodedLength() + gap;
    }

    // returns how much to add to offset
    private int generatePropertyEncodeDecode(
        final Token token,
        final String typeName,
        final StringBuilder encode,
        final StringBuilder decode,
        final int currentOffset)
    {

        final char varName = Character.toLowerCase(typeName.charAt(0));
        final String propertyName = formatPropertyName(token.name());
        final int gap = token.offset() - currentOffset;
        encode.append(generateEncodeOffset(gap, ""));
        decode.append(generateDecodeOffset(gap, ""));

        encode.append(String.format(
            "\tif err := %1$s.%2$s.Encode(writer, order); err != nil {\n" +
            "\t\treturn err\n" +
            "\t}\n",
            varName, propertyName));

        decode.append(String.format(
            "\tif %1$s.%2$sInActingVersion(actingVersion) {\n" +
            "\t\tif err := %1$s.%2$s.Decode(reader, order, actingVersion); err != nil {\n" +
            "\t\t\treturn err\n" +
            "\t\t}\n" +
            "\t}\n",
            varName, propertyName));

        return token.encodedLength() + gap;
    }

    // returns how much to add to offset
    private int generateVarDataEncodeDecode(
        final List<Token> tokens,
        final String typeName,
        final StringBuilder encode,
        final StringBuilder decode,
        final StringBuilder rc,
        final int currentOffset)
    {
        final Token signalToken = tokens.get(0);
        final char varName = Character.toLowerCase(typeName.charAt(0));
        final String propertyName = formatPropertyName(signalToken.name());

        // Offset handling
        final int gap = Math.max(signalToken.offset() - currentOffset, 0);
        encode.append(generateEncodeOffset(gap, ""));
        decode.append(generateDecodeOffset(gap, ""));

        // Write the group header (blocklength and numingroup)
        final String golangTypeForLength = golangTypeName(tokens.get(2).encoding().primitiveType());
        final String golangTypeForData = golangTypeName(tokens.get(3).encoding().primitiveType());

        generateCharacterEncodingRangeCheck(rc, varName + "." + propertyName, tokens.get(3));

        encode.append(String.format(
                "\tif err := binary.Write(writer, order, %1$s(len(%2$s.%3$s))); err != nil {\n" +
                "\t\treturn err\n" +
                "\t}\n" +
                "\tif err := binary.Write(writer, order, %2$s.%3$s); err != nil {\n" +
                "\t\treturn err\n" +
                "\t}\n",
                golangTypeForLength,
                varName,
                propertyName));

        // FIXME:performance we might check capacity before make(),
        decode.append(String.format(
            "\n" +
            "\tif %1$s.%2$sInActingVersion(actingVersion) {\n" +
            "\t\tvar %2$sLength %3$s\n" +
            "\t\tif err := binary.Read(reader, order, &%2$sLength); err != nil {\n" +
            "\t\t\treturn err\n" +
            "\t\t}\n" +
            "\t\t%1$s.%2$s = make([]%4$s, %2$sLength)\n" +
            "\t\tif err := binary.Read(reader, order, &%1$s.%2$s); err != nil {\n" +
            "\t\t\treturn err\n" +
            "\t\t}\n" +
            "\t}\n",
            varName,
            propertyName,
            golangTypeForLength,
            golangTypeForData));

        return gap;
    }

    // returns how much to add to offset
    private int generateGroupEncodeDecode(
        final List<Token> tokens,
        final String typeName,
        final StringBuilder encode,
        final StringBuilder decode,
        final StringBuilder rc,
        final int currentOffset)
    {
        final char varName = Character.toLowerCase(typeName.charAt(0));
        final Token signalToken = tokens.get(0);
        final String propertyName = formatPropertyName(signalToken.name());
        final String blockLengthType = golangTypeName(tokens.get(2).encoding().primitiveType());
        final String numInGroupType = golangTypeName(tokens.get(3).encoding().primitiveType());

        // Offset handling
        final int gap = Math.max(signalToken.offset() - currentOffset, 0);
        encode.append(generateEncodeOffset(gap, ""));
        decode.append(generateDecodeOffset(gap, ""));

        // Write/Read the group header
        encode.append(String.format(
            "\n\tvar %6$sBlockLength %1$s = %2$d\n" +
            "\tvar %6$sNumInGroup %3$s = %3$s(len(%4$s.%5$s))\n" +
            "\tif err := binary.Write(writer, order, %6$sBlockLength); err != nil {\n" +
            "\t\treturn err\n" +
            "\t}\n" +
            "\tif err := binary.Write(writer, order, %6$sNumInGroup); err != nil {\n" +
            "\t\treturn err\n" +
            "\t}\n",
            blockLengthType,
            signalToken.encodedLength(),
            numInGroupType,
            varName,
            toUpperFirstChar(signalToken.name()),
            propertyName));

        // Write/Read the group itself
        encode.append(String.format(
            "\tfor _, prop := range %1$s.%2$s {\n" +
            "\t\tif err := prop.Encode(writer, order); err != nil {\n" +
            "\t\t\treturn err\n" +
            "\t\t}\n",
            varName,
            toUpperFirstChar(signalToken.name())));

        // Read length/num
        decode.append(String.format(
            "\n" +
            "\tif %1$s.%2$sInActingVersion(actingVersion) {\n" +
            "\t\tvar %2$sBlockLength %3$s\n" +
            "\t\tvar %2$sNumInGroup %4$s\n" +
            "\t\tif err := binary.Read(reader, order, &%2$sBlockLength); err != nil {\n" +
            "\t\t\treturn err\n" +
            "\t\t}\n" +
            "\t\tif err := binary.Read(reader, order, &%2$sNumInGroup); err != nil {\n" +
            "\t\t\treturn err\n" +
            "\t\t}\n",
            varName,
            propertyName,
            blockLengthType,
            numInGroupType));

        // Read num elements
        decode.append(String.format(
            "\t\t%1$s.%2$s = make([]%3$s%2$s, %2$sNumInGroup)\n" +
            "\t\tfor i, _ := range %1$s.%2$s {\n" +
            "\t\t\tif err := %1$s.%2$s[i].Decode(reader, order, actingVersion, %4$sBlockLength); err != nil {\n" +
            "\t\t\t\treturn err\n" +
            "\t\t\t}\n" +
            "\t\t}\n",
            varName,
            toUpperFirstChar(signalToken.name()),
            typeName,
            propertyName));

        // Range check the group itself
        rc.append(String.format(
            "\tfor _, prop := range %1$s.%2$s {\n" +
            "\t\tif err := prop.RangeCheck(actingVersion, schemaVersion); err != nil {\n" +
            "\t\t\treturn err\n" +
            "\t\t}\n" +
            "\t}\n",
            varName,
            toUpperFirstChar(signalToken.name())));

        return gap;
    }


    // Recursively traverse groups to create the group properties
    private void generateGroupProperties(
        final StringBuilder sb,
        final List<Token> tokens,
        final String prefix)
    {
        for (int i = 0, size = tokens.size(); i < size; i++)
        {
            final Token token = tokens.get(i);
            if (token.signal() == Signal.BEGIN_GROUP)
            {

                final char varName = Character.toLowerCase(prefix.charAt(0));
                final String propertyName = formatPropertyName(token.name());

                generateId(sb, prefix, propertyName, token);
                generateSinceActingDeprecated(sb, prefix, propertyName, token);
                generateExtensibilityMethods(sb, prefix + propertyName, token);

                // Look inside for nested groups with extra prefix
                generateGroupProperties(
                    sb,
                    tokens.subList(i + 1, i + token.componentTokenCount() - 1),
                    prefix + propertyName);
                i += token.componentTokenCount() - 1;
            }
        }
    }

    private void generateGroups(final StringBuilder sb, final List<Token> tokens, final String prefix)
    {
        for (int i = 0, size = tokens.size(); i < size; i++)
        {
            final Token groupToken = tokens.get(i);
            if (groupToken.signal() != Signal.BEGIN_GROUP)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_GROUP: token=" + groupToken);
            }

            // Make a unique Group name by adding our parent
            final String groupName = prefix + formatTypeName(groupToken.name());
            final String golangTypeForNumInGroup = golangTypeName(tokens.get(i + 3).encoding().primitiveType());

            ++i;
            final int groupHeaderTokenCount = tokens.get(i).componentTokenCount();
            i += groupHeaderTokenCount;

            final List<Token> fields = new ArrayList<>();
            i = collectFields(tokens, i, fields);
            generateFields(sb, groupName, fields, prefix);

            final List<Token> groups = new ArrayList<>();
            i = collectGroups(tokens, i, groups);
            generateGroups(sb, groups, groupName);

            final List<Token> varData = new ArrayList<>();
            i = collectVarData(tokens, i, varData);
            generateVarData(sb, formatTypeName(groupName), varData, prefix);
        }
    }

    private void generateVarData(
        final StringBuilder sb,
        final String typeName,
        final List<Token> tokens,
        final String prefix)
    {
        for (int i = 0, size = tokens.size(); i < size;)
        {
            final Token token = tokens.get(i);
            if (token.signal() != Signal.BEGIN_VAR_DATA)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_VAR_DATA: token=" + token);
            }

            final String propertyName = toUpperFirstChar(token.name());
            final String characterEncoding = tokens.get(i + 3).encoding().characterEncoding();
            final Token lengthToken = tokens.get(i + 2);
            final int lengthOfLengthField = lengthToken.encodedLength();

            generateFieldMetaAttributeMethod(sb, typeName, token, prefix);

            generateVarDataDescriptors(
                sb, token, typeName, propertyName, characterEncoding, lengthOfLengthField);

            i += token.componentTokenCount();
        }
    }

    private void generateVarDataDescriptors(
        final StringBuilder sb,
        final Token token,
        final String typeName,
        final String propertyName,
        final String characterEncoding,
        final Integer lengthOfLengthField)
    {
        final char varName = Character.toLowerCase(typeName.charAt(0));

        generateSinceActingDeprecated(sb, typeName, propertyName, token);
        sb.append(String.format(
            "\nfunc (%1$s %2$s) %3$sCharacterEncoding() string {\n" +
             "\treturn \"%4$s\"\n" +
            "}\n" +
            "\nfunc (%1$s %2$s) %3$sHeaderLength() uint64 {\n" +
            "\treturn %5$s\n" +
            "}\n",
            varName,
            typeName,
            propertyName,
            characterEncoding,
            lengthOfLengthField));
    }

    private void generateChoiceSet(final List<Token> tokens) throws IOException
    {
        final Token choiceToken = tokens.get(0);
        final String choiceName = formatTypeName(choiceToken.name());
        final char varName = Character.toLowerCase(choiceName.charAt(0));
        final StringBuilder sb = new StringBuilder();

        try (Writer out = outputManager.createOutput(choiceName))
        {
            // Initialize the imports
            imports = new TreeSet<>();
            imports.add("io");
            imports.add("encoding/binary");

            generateChoiceDecls(
                sb,
                choiceName,
                tokens.subList(1, tokens.size() - 1),
                choiceToken);

            generateChoiceEncodeDecode(sb, choiceName, choiceToken);

            // EncodedLength
            sb.append(String.format(
                "\nfunc (%1$s %2$s) EncodedLength() int64 {\n" +
                "\treturn %3$s\n" +
                "}\n",
                varName,
                choiceName,
                choiceToken.encodedLength()));

            for (final Token token : tokens.subList(1, tokens.size() - 1))
            {
                generateSinceActingDeprecated(sb, choiceName, token.name(), token);
            }
            out.append(generateFileHeader(ir.namespaces(), choiceName));
            out.append(sb);
        }
    }

    private void generateEnum(final List<Token> tokens) throws IOException
    {
        final Token enumToken = tokens.get(0);
        final String enumName = formatTypeName(tokens.get(0).name());
        final char varName = Character.toLowerCase(enumName.charAt(0));

        final StringBuilder sb = new StringBuilder();

        try (Writer out = outputManager.createOutput(enumName))
        {
            // Initialize the imports
            imports = new TreeSet<>();
            imports.add("io");
            imports.add("encoding/binary");

            generateEnumDecls(
                sb,
                enumName,
                golangTypeName(tokens.get(0).encoding().primitiveType()),
                tokens.subList(1, tokens.size() - 1),
                enumToken);

            generateEnumEncodeDecode(sb, enumName, enumToken);

            // EncodedLength
            sb.append(String.format(
                "\nfunc (%1$s %2$sEnum) EncodedLength() int64 {\n" +
                "\treturn %3$s\n" +
                "}\n",
                varName,
                enumName,
                enumToken.encodedLength()));

            for (final Token token : tokens.subList(1, tokens.size() - 1))
            {
                generateSinceActingDeprecated(sb, enumName + "Enum", token.name(), token);
            }

            out.append(generateFileHeader(ir.namespaces(), enumName));
            out.append(sb);
        }
    }

    private void generateComposite(
        final List<Token> tokens,
        final String namePrefix) throws IOException
    {
        final String compositeName = namePrefix + formatTypeName(tokens.get(0).name());
        final StringBuilder sb = new StringBuilder();

        try (Writer out = outputManager.createOutput(compositeName))
        {
            // Initialize the imports
            imports = new TreeSet<>();
            imports.add("io");
            imports.add("encoding/binary");

            generateTypeDeclaration(sb, compositeName);
            generateTypeBodyComposite(sb, compositeName, tokens.subList(1, tokens.size() - 1));

            generateEncodeDecode(sb, compositeName, tokens.subList(1, tokens.size() - 1), false, false);
            generateEncodedLength(sb, compositeName, tokens.get(0).encodedLength());

            generateCompositePropertyElements(sb, compositeName, tokens.subList(1, tokens.size() - 1));

            // The FileHeader needs to know which imports to add so
            // it's created last once that's known.
            out.append(generateFileHeader(ir.namespaces(), compositeName));
            out.append(sb);
        }
    }

    private void generateEnumDecls(
        final StringBuilder sb,
        final String enumName,
        final String golangType,
        final List<Token> tokens,
        final Token encodingToken)
    {
        final Encoding encoding = encodingToken.encoding();

        // gofmt lines up the types and we don't want it to have to rewrite
        // our generated files. To line things up we need to know the longest
        // string length and then fill with whitespace
        final String nullValue = "NullValue";
        int longest = nullValue.length();
        for (final Token token : tokens)
        {
            longest = Math.max(longest, token.name().length());
        }

        // Enums are modelled as a struct and we export an instance so
        // you can reference known values as expected.
        sb.append(String.format(
             "type %1$sEnum %2$s\n" +
             "type %1$sValues struct {\n",
                      enumName, golangType));

        for (final Token token : tokens)
        {
            sb.append(String.format(
                "\t%1$s%2$s%3$sEnum\n",
                token.name(),
                String.format(String.format("%%%ds", longest - token.name().length() + 1), " "),
                enumName));
        }

        // Add the NullValue
        sb.append(String.format(
            "\t%1$s%2$s%3$sEnum\n" +
            "}\n",
            nullValue,
            String.format(String.format("%%%ds", longest - nullValue.length() + 1), " "),
            enumName));

        // And now the Enum Values expressed as a variable
        sb.append(String.format(
             "\nvar %1$s = %1$sValues{",
             enumName));
        for (final Token token : tokens)
        {
            sb.append(generateLiteral(token.encoding().primitiveType(), token.encoding().constValue().toString())).append(", ");
        }
        // Add the NullValue and close
        sb.append(encodingToken.encoding().applicableNullValue().toString()).append("}\n");

        return;
    }

    private void generateChoiceDecls(
        final StringBuilder sb,
        final String choiceName,
        final List<Token> tokens,
        final Token encodingToken)
    {
        final Encoding encoding = encodingToken.encoding();

        // gofmt lines up the types and we don't want it to have to rewrite
        // our generated files. To line things up we need to know the longest
        // string length and then fill with whitespace
        int longest = 0;
        for (final Token token : tokens)
        {
            longest = Math.max(longest, token.name().length());
        }

        // A ChoiceSet is modelled as an array of bool of size
        // encodedLength in bits (akin to bits in a bitfield).
        // Choice values are modelled as a struct and we export an
        // instance so you can reference known values by name.
        sb.append(String.format(
            "type %1$s [%2$d]bool\n" +
            "type %1$sChoiceValue uint8\n" +
            "type %1$sChoiceValues struct {\n",
            choiceName, encodingToken.encodedLength() * 8));

        for (final Token token : tokens)
        {
            sb.append(String.format(
                "\t%1$s%2$s%3$sChoiceValue\n",
                toUpperFirstChar(token.name()),
                String.format(String.format("%%%ds", longest - token.name().length() + 1), " "),
                toUpperFirstChar(encodingToken.name())));
        }

        sb.append("}\n");

        // And now the Values expressed as a variable
        sb.append(String.format(
             "\nvar %1$sChoice = %1$sChoiceValues{",
             choiceName));

        String comma = "";
        for (final Token token : tokens)
        {
            sb.append(comma)
                .append(generateLiteral(token.encoding().primitiveType(), token.encoding().constValue().toString()));
            comma = ", ";
        }
        sb.append("}\n");

        return;
    }

    private CharSequence generateFileHeader(
        final CharSequence[] namespaces,
        final String typeName)
    {
        final StringBuilder sb = new StringBuilder();

        sb.append("// Generated SBE (Simple Binary Encoding) message codec\n\n");
        sb.append(String.format(
            "package %1$s\n" +
            "\n" +
            "import (\n",
            String.join("_", namespaces).toLowerCase().replace('.', '_').replace(' ', '_')));

        for (final String s : imports)
        {
            sb.append("\t\"").append(s).append("\"\n");
        }

        sb.append(")\n\n");
        return sb;
    }

    private static void generateTypeDeclaration(
        final StringBuilder sb,
        final String typeName)
    {
        sb.append(String.format("type %s struct {\n", typeName));
    }

    private void generateTypeBody(
        final StringBuilder sb,
        final String typeName,
        final List<Token> tokens)
    {
        // gofmt lines up the types and we don't want it to have to rewrite
        // our generated files. To line things up we need to know the longest
        // string length and then fill with whitespace
        int longest = 0;
        for (int i = 0; i < tokens.size(); i++)
        {
            final Token token = tokens.get(i);
            final String propertyName = formatPropertyName(token.name());

            switch (token.signal())
            {
                case BEGIN_GROUP:
                case BEGIN_VAR_DATA:
                    longest = Math.max(longest, propertyName.length());
                    i += token.componentTokenCount() - 1;
                    break;

                case BEGIN_FIELD:
                    longest = Math.max(longest, propertyName.length());
                    break;
                case END_GROUP:
                    i = tokens.size(); // terminate the loop
                    break;
            }
        }

        final StringBuilder nested = new StringBuilder(); // For nested groups
        for (int i = 0; i < tokens.size(); i++)
        {
            final Token signalToken = tokens.get(i);
            final String propertyName = formatPropertyName(signalToken.name());

            switch (signalToken.signal())
            {
                case BEGIN_FIELD:
                    if (tokens.size() > i + 1)
                    {
                        final Token encodingToken = tokens.get(i + 1);

                        // it's an array if length > 1, otherwise normally not
                        String arrayspec = "";
                        if (encodingToken.arrayLength() > 1)
                        {
                            arrayspec = "[" + encodingToken.arrayLength() + "]";
                        }

                        switch (encodingToken.signal())
                        {
                            case BEGIN_ENUM:
                                sb.append("\t").append(propertyName)
                                    .append(String.format(String.format("%%%ds", longest - propertyName.length() + 1), " "))
                                    .append(arrayspec)
                                    .append(encodingToken.name())
                                    .append("Enum\n");
                                break;

                            case BEGIN_SET:
                                sb.append("\t")
                                    .append(propertyName)
                                    .append(String.format(String.format("%%%ds", longest - propertyName.length() + 1), " "))
                                    .append(arrayspec)
                                    .append(encodingToken.name())
                                    .append("\n");
                                break;

                            default:
                                // If the type is primitive then use the golang naming for it
                                String golangType;
                                golangType = golangTypeName(encodingToken.encoding().primitiveType());
                                if (golangType == null)
                                {
                                    golangType = toUpperFirstChar(encodingToken.name());
                                }
                                // If primitiveType="char" and presence="constant"
                                // then this is actually a character array which
                                // can be of length 1
                                if (encodingToken.isConstantEncoding() && encodingToken.encoding().primitiveType() == CHAR)
                                {
                                    arrayspec = "[" + encodingToken.encoding().constValue().size() + "]";
                                }
                                sb.append("\t").append(propertyName)
                                    .append(String.format(String.format("%%%ds", longest - propertyName.length() + 1), " "))
                                    .append(arrayspec).append(golangType).append("\n");
                                break;
                        }
                        i++;
                    }
                    break;

                case BEGIN_GROUP:
                    sb.append(String.format(
                        "\t%1$s%2$s[]%3$s%1$s\n",
                        toUpperFirstChar(signalToken.name()),
                        String.format(String.format("%%%ds", longest - propertyName.length() + 1), " "),
                        typeName));
                    generateTypeDeclaration(
                        nested,
                        typeName + toUpperFirstChar(signalToken.name()));
                    generateTypeBody(
                        nested,
                        typeName + toUpperFirstChar(signalToken.name()),
                        tokens.subList(i + 1, tokens.size() - 1));
                    i += signalToken.componentTokenCount() - 1;
                    break;

                case END_GROUP:
                    // Close the group and unwind
                    sb.append("}\n");
                    sb.append(nested);
                    return;

                case BEGIN_VAR_DATA:
                    sb.append(String.format(
                        "\t%1$s%2$s[]%3$s\n",
                        toUpperFirstChar(signalToken.name()),
                        String.format(String.format("%%%ds", longest - propertyName.length() + 1), " "),

                        golangTypeName(tokens.get(i + 3).encoding().primitiveType())));
                    break;

                default:
                    break;
            }
        }
        sb.append("}\n");
        sb.append(nested);
    }


    private void generateCompositePropertyElements(
        final StringBuilder sb,
        final String containingTypeName,
        final List<Token> tokens)
    {
        for (int i = 0; i < tokens.size();)
        {
            final Token token = tokens.get(i);
            final String propertyName = formatPropertyName(token.name());

            // Write {Min,Max,Null}Value
            switch (token.signal())
            {
                case ENCODING:
                    generateMinMaxNull(sb, containingTypeName, propertyName, token);
                    generateCharacterEncoding(sb, containingTypeName, propertyName, token);
                    break;

                default:
                    break;
            }

            switch (token.signal())
            {
                case ENCODING:
                case BEGIN_ENUM:
                case BEGIN_SET:
                case BEGIN_COMPOSITE:
                    generateSinceActingDeprecated(sb, containingTypeName, propertyName, token);
                    break;
                default:
                    break;
            }
            i += tokens.get(i).componentTokenCount();
        }

        return;
    }

    private void generateMinMaxNull(
        final StringBuilder sb,
        final String typeName,
        final String propertyName,
        final Token token)
    {

        final Encoding encoding = token.encoding();
        final PrimitiveType primitiveType = encoding.primitiveType();
        final String golangTypeName = golangTypeName(primitiveType);
        final CharSequence nullValueString = generateNullValueLiteral(primitiveType, encoding);
        final CharSequence maxValueString = generateMaxValueLiteral(primitiveType, encoding);
        final CharSequence minValueString = generateMinValueLiteral(primitiveType, encoding);

        // MinValue
        sb.append(String.format(
            "\nfunc (%1$s %2$s) %3$sMinValue() %4$s {\n" +
            "\treturn %5$s\n" +
            "}\n",
            Character.toLowerCase(typeName.charAt(0)),
            typeName,
            propertyName,
            golangTypeName,
            minValueString));

        // MaxValue
        sb.append(String.format(
            "\nfunc (%1$s %2$s) %3$sMaxValue() %4$s {\n" +
            "\treturn %5$s\n" +
            "}\n",
            Character.toLowerCase(typeName.charAt(0)),
            typeName,
            propertyName,
            golangTypeName,
            maxValueString));

        // NullValue
        sb.append(String.format(
            "\nfunc (%1$s %2$s) %3$sNullValue() %4$s {\n" +
            "\treturn %5$s\n" +
            "}\n",
            Character.toLowerCase(typeName.charAt(0)),
            typeName,
            propertyName,
            golangTypeName,
            nullValueString));

        return;
    }

    private void generateCharacterEncoding(
        final StringBuilder sb,
        final String typeName,
        final String propertyName,
        final Token token)
    {
        if (token.encoding().primitiveType() == CHAR && token.arrayLength() > 1)
        {
            sb.append(String.format(
                "\nfunc (%1$s %2$s) %3$sCharacterEncoding() string {\n" +
                "\treturn \"%4$s\"\n" +
                "}\n",
                Character.toLowerCase(typeName.charAt(0)),
                typeName,
                propertyName,
                token.encoding().characterEncoding()));
        }
        return;
    }

    private void generateId(
        final StringBuilder sb,
        final String typeName,
        final String propertyName,
        final Token token)
    {
        sb.append(String.format(
            "\nfunc (%1$s %2$s) %3$sId() uint16 {\n" +
            "\treturn %4$s\n" +
            "}\n",
            Character.toLowerCase(typeName.charAt(0)),
            typeName,
            propertyName,
            token.id()));
        return;
    }

    private void generateSinceActingDeprecated(
        final StringBuilder sb,
        final String typeName,
        final String propertyName,
        final Token token)
    {
        sb.append(String.format(
            "\nfunc (%1$s %2$s) %3$sSinceVersion() uint16 {\n" +
            "\treturn %4$s\n" +
            "}\n" +
            "\nfunc (%1$s %2$s) %3$sInActingVersion(actingVersion uint16) bool {\n" +
            "\treturn actingVersion >= %1$s.%3$sSinceVersion()\n" +
            "}\n" +
            "\nfunc (%1$s %2$s) %3$sDeprecated() uint16 {\n" +
            "\treturn %5$s\n" +
            "}\n",
            Character.toLowerCase(typeName.charAt(0)),
            typeName,
            propertyName,
            token.version(),
            token.deprecated()));
        return;
    }

    private void generateTypeBodyComposite(
        final StringBuilder sb,
        final String typeName,
        final List<Token> tokens) throws IOException
    {
        // gofmt lines up the types and we don't want it to have to rewrite
        // our generated files. To line things up we need to know the longest
        // string length and then fill with whitespace
        int longest = 0;
        for (int i = 0; i < tokens.size(); i++)
        {
            final Token token = tokens.get(i);
            final String propertyName = formatPropertyName(token.name());

            switch (token.signal())
            {
                case BEGIN_GROUP:
                case BEGIN_COMPOSITE:
                case BEGIN_VAR_DATA:
                    longest = Math.max(longest, propertyName.length());
                    i += token.componentTokenCount() - 1;
                    break;
                case BEGIN_ENUM:
                case BEGIN_SET:
                case ENCODING:
                    longest = Math.max(longest, propertyName.length());
                    break;
                case END_COMPOSITE:
                    i = tokens.size(); // terminate the loop
                    break;
            }
        }

        for (int i = 0; i < tokens.size(); i++)
        {
            final Token token = tokens.get(i);
            final String propertyName = formatPropertyName(token.name());
            int arrayLength = token.arrayLength();

            switch (token.signal())
            {
                case ENCODING:
                    // if a primitiveType="char" and presence="constant" then this is actually a character array
                    if (token.isConstantEncoding() && token.encoding().primitiveType() == CHAR)
                    {
                        arrayLength = token.encoding().constValue().size(); // can be 1
                        sb.append("\t").append(propertyName)
                            .append(String.format(String.format("%%%ds", longest - propertyName.length() + 1), " "))
                            .append("[").append(arrayLength).append("]")
                            .append(golangTypeName(token.encoding().primitiveType())).append("\n");
                    }
                    else
                    {
                        sb.append("\t").append(propertyName)
                            .append(String.format(String.format("%%%ds", longest - propertyName.length() + 1), " "))
                            .append((arrayLength > 1) ? ("[" + arrayLength + "]") : "")
                            .append(golangTypeName(token.encoding().primitiveType())).append("\n");
                    }
                    break;

                case BEGIN_ENUM:
                    sb.append("\t").append(propertyName)
                        .append(String.format(String.format("%%%ds", longest - propertyName.length() + 1), " "))
                        .append((arrayLength > 1) ? ("[" + arrayLength + "]") : "").append(propertyName).append("Enum\n");
                    break;

                case BEGIN_SET:
                    sb.append("\t").append(propertyName)
                        .append(String.format(String.format("%%%ds", longest - propertyName.length() + 1), " "))
                        .append((arrayLength > 1) ? ("[" + arrayLength + "]") : "").append(propertyName).append("\n");
                    break;

                case BEGIN_COMPOSITE:
                    // recurse
                    generateComposite(tokens.subList(i, tokens.size()), typeName);
                    i += token.componentTokenCount() - 2;

                    sb.append("\t").append(propertyName)
                        .append(String.format(String.format("%%%ds", longest - propertyName.length() + 1), " "))
                        .append((arrayLength > 1) ? ("[" + arrayLength + "]") : "")
                        .append(typeName).append(propertyName).append("\n");
                    break;
            }
        }
        sb.append("}\n");

        return;
    }

    private void generateEncodedLength(
        final StringBuilder sb,
        final String typeName,
        final int size)
    {
        sb.append(String.format(
            "\nfunc (%1$s %2$s) EncodedLength() int64 {\n" +
            "\treturn %3$s\n" +
            "}\n",
            Character.toLowerCase(typeName.charAt(0)),
            typeName,
            size));

        return;
    }

    private void generateMessageCode(
        final StringBuilder sb,
        final String typeName,
        final List<Token> tokens)
    {
        final Token token = tokens.get(0);
        final String semanticType = token.encoding().semanticType() == null ? "" : token.encoding().semanticType();
        final String blockLengthType = golangTypeName(ir.headerStructure().blockLengthType());
        final String templateIdType = golangTypeName(ir.headerStructure().templateIdType());
        final String schemaIdType = golangTypeName(ir.headerStructure().schemaIdType());
        final String schemaVersionType = golangTypeName(ir.headerStructure().schemaVersionType());


        generateEncodeDecode(sb, typeName, tokens, true, true);

        sb.append(String.format(
            "\nfunc (%1$s %2$s) SbeBlockLength() (blockLength %3$s) {\n" +
            "\treturn %4$s\n" +
            "}\n" +
            "\nfunc (%1$s %2$s) SbeTemplateId() (templateId %5$s) {\n" +
            "\treturn %6$s\n" +
            "}\n" +
            "\nfunc (%1$s %2$s) SbeSchemaId() (schemaId %7$s) {\n" +
            "\treturn %8$s\n" +
            "}\n" +
            "\nfunc (%1$s %2$s) SbeSchemaVersion() (schemaVersion %9$s) {\n" +
            "\treturn %10$s\n" +
            "}\n" +
            "\nfunc (%1$s %2$s) SbeSemanticType() (semanticType []byte) {\n" +
            "\treturn []byte(\"%11$s\")\n" +
            "}\n",
            Character.toLowerCase(typeName.charAt(0)),
            typeName,
            blockLengthType,
            generateLiteral(ir.headerStructure().blockLengthType(), Integer.toString(token.encodedLength())),
            templateIdType,
            generateLiteral(ir.headerStructure().templateIdType(), Integer.toString(token.id())),
            schemaIdType,
            generateLiteral(ir.headerStructure().schemaIdType(), Integer.toString(ir.id())),
            schemaVersionType,
            generateLiteral(ir.headerStructure().schemaVersionType(), Integer.toString(ir.version())),
            semanticType));

        return;
    }

    // Used for groups which need to know the schema's definition
    // of blocklength and version to check for extensions
    private void generateExtensibilityMethods(
        final StringBuilder sb,
        final String typeName,
        final Token token)
    {
        sb.append(String.format(
            "\nfunc (%1$s %2$s) SbeBlockLength() (blockLength %3$s) {\n" +
            "\treturn %4$s\n" +
            "}\n" +
            "\nfunc (%1$s %2$s) SbeSchemaVersion() (schemaVersion %5$s) {\n" +
            "\treturn %6$s\n" +
            "}\n",
            Character.toLowerCase(typeName.charAt(0)),
            typeName,
            golangTypeName(ir.headerStructure().blockLengthType()),
            generateLiteral(ir.headerStructure().blockLengthType(), Integer.toString(token.encodedLength())),
            golangTypeName(ir.headerStructure().schemaVersionType()),
            generateLiteral(ir.headerStructure().schemaVersionType(), Integer.toString(ir.version()))));

        return;
    }

    private void generateFields(
        final StringBuilder sb,
        final String containingTypeName,
        final List<Token> tokens,
        final String prefix)
    {
        for (int i = 0, size = tokens.size(); i < size; i++)
        {
            final Token signalToken = tokens.get(i);
            if (signalToken.signal() == Signal.BEGIN_FIELD)
            {
                final Token encodingToken = tokens.get(i + 1);
                final String propertyName = formatPropertyName(signalToken.name());

                generateId(sb, containingTypeName, propertyName, signalToken);
                generateSinceActingDeprecated(sb, containingTypeName, propertyName, signalToken);
                generateFieldMetaAttributeMethod(sb, containingTypeName, signalToken, prefix);

                switch (encodingToken.signal())
                {
                    case ENCODING:
                        generateMinMaxNull(sb, containingTypeName, propertyName, encodingToken);
                        generateCharacterEncoding(sb, containingTypeName, propertyName, encodingToken);
                        break;

                    case BEGIN_ENUM:
                        break;

                    case BEGIN_SET:
                        break;

                    case BEGIN_COMPOSITE:
                        break;

                    default:
                        break;
                }
            }
        }

        return;
    }

    private static void generateFieldMetaAttributeMethod(
        final StringBuilder sb,
        final String containingTypeName,
        final Token token,
        final String prefix)
    {
        final Encoding encoding = token.encoding();
        final String epoch = encoding.epoch() == null ? "" : encoding.epoch();
        final String timeUnit = encoding.timeUnit() == null ? "" : encoding.timeUnit();
        final String semanticType = encoding.semanticType() == null ? "" : encoding.semanticType();
        sb.append(String.format(
            "\nfunc (%1$s %2$s) %3$sMetaAttribute(meta int) string {\n" +
            "\tswitch meta {\n" +
            "\tcase 1:\n" +
            "\t\treturn \"%4$s\"\n" +
            "\tcase 2:\n" +
            "\t\treturn \"%5$s\"\n" +
            "\tcase 3:\n" +
            "\t\treturn \"%6$s\"\n" +
            "\t}\n" +
            "\treturn \"\"\n" +
            "}\n",
            Character.toLowerCase(containingTypeName.charAt(0)),
            containingTypeName,
            toUpperFirstChar(token.name()),
            epoch,
            timeUnit,
            semanticType));
    }

    private CharSequence generateMinValueLiteral(final PrimitiveType primitiveType, final Encoding encoding)
    {
        if (null == encoding.maxValue())
        {
            switch (primitiveType)
            {
                case CHAR:
                    return "byte(32)";
                case INT8:
                    imports.add("math");
                    return "math.MinInt8 + 1";
                case INT16:
                    imports.add("math");
                    return "math.MinInt16 + 1";
                case INT32:
                    imports.add("math");
                    return "math.MinInt32 + 1";
                case INT64:
                    imports.add("math");
                    return "math.MinInt64 + 1";
                case UINT8:
                case UINT16:
                case UINT32:
                case UINT64:
                    return "0";
                case FLOAT:
                    imports.add("math");
                    return "-math.MaxFloat32";
                case DOUBLE:
                    imports.add("math");
                    return "-math.MaxFloat64";

            }
        }

        return generateLiteral(primitiveType, encoding.applicableMinValue().toString());
    }

    private CharSequence generateMaxValueLiteral(final PrimitiveType primitiveType, final Encoding encoding)
    {
        if (null == encoding.maxValue())
        {
            switch (primitiveType)
            {
                case CHAR:
                    return "byte(126)";
                case INT8:
                    imports.add("math");
                    return "math.MaxInt8";
                case INT16:
                    imports.add("math");
                    return "math.MaxInt16";
                case INT32:
                    imports.add("math");
                    return "math.MaxInt32";
                case INT64:
                    imports.add("math");
                    return "math.MaxInt64";
                case UINT8:
                    imports.add("math");
                    return "math.MaxUint8 - 1";
                case UINT16:
                    imports.add("math");
                    return "math.MaxUint16 - 1";
                case UINT32:
                    imports.add("math");
                    return "math.MaxUint32 - 1";
                case UINT64:
                    imports.add("math");
                    return "math.MaxUint64 - 1";
                case FLOAT:
                    imports.add("math");
                    return "math.MaxFloat32";
                case DOUBLE:
                    imports.add("math");
                    return "math.MaxFloat64";
            }
        }

        return generateLiteral(primitiveType, encoding.applicableMaxValue().toString());
    }

    private CharSequence generateNullValueLiteral(final PrimitiveType primitiveType, final Encoding encoding)
    {
        if (null == encoding.nullValue())
        {
            switch (primitiveType)
            {
                case INT8:
                    imports.add("math");
                    return "math.MinInt8";
                case INT16:
                    imports.add("math");
                    return "math.MinInt16";
                case INT32:
                    imports.add("math");
                    return "math.MinInt32";
                case INT64:
                    imports.add("math");
                    return "math.MinInt64";
                case UINT8:
                    imports.add("math");
                    return "math.MaxUint8";
                case UINT16:
                    imports.add("math");
                    return "math.MaxUint16";
                case UINT32:
                    imports.add("math");
                    return "math.MaxUint32";
                case UINT64:
                    imports.add("math");
                    return "math.MaxUint64";
            }
        }

        return generateLiteral(primitiveType, encoding.applicableNullValue().toString());
    }

    private CharSequence generateLiteral(final PrimitiveType type, final String value)
    {
        String literal = "";

        final String castType = golangTypeName(type);
        switch (type)
        {
            case CHAR:
            case UINT8:
            case UINT16:
            case INT8:
            case INT16:
                literal = value;
                break;

            case UINT32:
            case INT32:
                literal = value;
                break;
            case INT64:
            case UINT64:
                literal = castType + "(" + value + ")";
                break;

            case FLOAT:
                literal = "float32(" + (value.endsWith("NaN") ? "math.NaN()" : value) + ")";
                break;

            case DOUBLE:
                literal = value.endsWith("NaN") ? "math.NaN()" : value;
                break;
        }

        return literal;
    }
}
