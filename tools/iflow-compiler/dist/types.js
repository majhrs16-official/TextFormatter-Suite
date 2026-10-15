"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.REGISTER_NAMES = exports.Decision = exports.PropertyId = exports.Scope = exports.ServiceId = exports.Register = exports.INSTRUCTION_SIZES = exports.Opcode = void 0;
/**
 * iFlow VM Opcode Definitions
 * Matches the Java ISA specification exactly
 */
var Opcode;
(function (Opcode) {
    // Data transfer (0x00-0x0F)
    Opcode[Opcode["LI"] = 0] = "LI";
    Opcode[Opcode["MOV"] = 1] = "MOV";
    Opcode[Opcode["LDB"] = 2] = "LDB";
    Opcode[Opcode["LDW"] = 3] = "LDW";
    Opcode[Opcode["STB"] = 4] = "STB";
    Opcode[Opcode["STW"] = 5] = "STW";
    Opcode[Opcode["COPY"] = 6] = "COPY";
    Opcode[Opcode["MOVE"] = 7] = "MOVE";
    // Arithmetic (0x20-0x27)
    Opcode[Opcode["ADD"] = 32] = "ADD";
    Opcode[Opcode["SUB"] = 33] = "SUB";
    Opcode[Opcode["MUL"] = 34] = "MUL";
    Opcode[Opcode["DIV"] = 35] = "DIV";
    Opcode[Opcode["MOD"] = 36] = "MOD";
    // Logic (0x28-0x2B)
    Opcode[Opcode["AND"] = 40] = "AND";
    Opcode[Opcode["OR"] = 41] = "OR";
    Opcode[Opcode["XOR"] = 42] = "XOR";
    Opcode[Opcode["NOT"] = 43] = "NOT";
    // Shifts (0x2C-0x2E)
    Opcode[Opcode["SHL"] = 44] = "SHL";
    Opcode[Opcode["SHR"] = 45] = "SHR";
    Opcode[Opcode["SAR"] = 46] = "SAR";
    // Comparison (0x30-0x31)
    Opcode[Opcode["CMP"] = 48] = "CMP";
    Opcode[Opcode["CMPU"] = 49] = "CMPU";
    // Flow control (0x40-0x4F)
    Opcode[Opcode["JMP"] = 64] = "JMP";
    Opcode[Opcode["JZ"] = 65] = "JZ";
    Opcode[Opcode["JNZ"] = 66] = "JNZ";
    Opcode[Opcode["JC"] = 67] = "JC";
    Opcode[Opcode["JV"] = 68] = "JV";
    Opcode[Opcode["CALL"] = 72] = "CALL";
    Opcode[Opcode["RET"] = 73] = "RET";
    // Services (0x60-0x6F)
    Opcode[Opcode["HOST"] = 96] = "HOST";
    // Control (0x68-0x6F)
    Opcode[Opcode["HALT"] = 104] = "HALT";
    Opcode[Opcode["TRAP"] = 105] = "TRAP";
})(Opcode || (exports.Opcode = Opcode = {}));
/**
 * Instruction sizes in bytes
 */
exports.INSTRUCTION_SIZES = {
    [Opcode.LI]: 4, // RI16
    [Opcode.MOV]: 2, // RR (packed)
    [Opcode.LDB]: 4, // MEM: opcode + reg + vaddr16
    [Opcode.LDW]: 4,
    [Opcode.STB]: 4,
    [Opcode.STW]: 4,
    [Opcode.COPY]: 8, // BLOCK: srcVaddr16 + dstVaddr16 + len16
    [Opcode.MOVE]: 8,
    [Opcode.ADD]: 2,
    [Opcode.SUB]: 2,
    [Opcode.MUL]: 2,
    [Opcode.DIV]: 2,
    [Opcode.MOD]: 2,
    [Opcode.AND]: 2,
    [Opcode.OR]: 2,
    [Opcode.XOR]: 2,
    [Opcode.NOT]: 2,
    [Opcode.SHL]: 2,
    [Opcode.SHR]: 2,
    [Opcode.SAR]: 2,
    [Opcode.CMP]: 2,
    [Opcode.CMPU]: 2,
    [Opcode.JMP]: 3,
    [Opcode.JZ]: 3,
    [Opcode.JNZ]: 3,
    [Opcode.JC]: 3,
    [Opcode.JV]: 3,
    [Opcode.CALL]: 3,
    [Opcode.RET]: 2,
    [Opcode.HOST]: 2,
    [Opcode.HALT]: 1,
    [Opcode.TRAP]: 2,
};
/**
 * Register indices
 */
var Register;
(function (Register) {
    Register[Register["R0"] = 0] = "R0";
    Register[Register["R1"] = 1] = "R1";
    Register[Register["R2"] = 2] = "R2";
    Register[Register["R3"] = 3] = "R3";
    Register[Register["R4"] = 4] = "R4";
    Register[Register["R5"] = 5] = "R5";
    Register[Register["R6"] = 6] = "R6";
    Register[Register["R7"] = 7] = "R7";
    Register[Register["IP"] = 8] = "IP";
    Register[Register["SP"] = 9] = "SP";
    Register[Register["FP"] = 10] = "FP";
    Register[Register["FL"] = 11] = "FL";
})(Register || (exports.Register = Register = {}));
/**
 * Service IDs (matching Java HostServiceRegistry)
 */
var ServiceId;
(function (ServiceId) {
    // Property services
    ServiceId[ServiceId["PROPERTY_READ"] = 256] = "PROPERTY_READ";
    ServiceId[ServiceId["PROPERTY_WRITE"] = 257] = "PROPERTY_WRITE";
    ServiceId[ServiceId["PROPERTY_EXISTS"] = 258] = "PROPERTY_EXISTS";
    // Permission services
    ServiceId[ServiceId["PERMISSION_CHECK"] = 512] = "PERMISSION_CHECK";
    ServiceId[ServiceId["PERMISSION_QUERY"] = 513] = "PERMISSION_QUERY";
    // Routing services
    ServiceId[ServiceId["ROUTE_DECISION"] = 768] = "ROUTE_DECISION";
    ServiceId[ServiceId["ROUTE_REDIRECT"] = 769] = "ROUTE_REDIRECT";
    ServiceId[ServiceId["ROUTE_RATE_LIMIT"] = 770] = "ROUTE_RATE_LIMIT";
    // State services
    ServiceId[ServiceId["STATE_READ"] = 1280] = "STATE_READ";
    ServiceId[ServiceId["STATE_WRITE"] = 1281] = "STATE_WRITE";
    // Channel services
    ServiceId[ServiceId["CHANNEL_RESOLVE"] = 1536] = "CHANNEL_RESOLVE";
    ServiceId[ServiceId["CHANNEL_POLICY"] = 1537] = "CHANNEL_POLICY";
    // Message services
    ServiceId[ServiceId["MESSAGE_CLONE"] = 1792] = "MESSAGE_CLONE";
    ServiceId[ServiceId["MESSAGE_ID"] = 1793] = "MESSAGE_ID";
})(ServiceId || (exports.ServiceId = ServiceId = {}));
/**
 * Property scopes
 */
var Scope;
(function (Scope) {
    Scope[Scope["MESSAGE"] = 1] = "MESSAGE";
    Scope[Scope["EMITTER"] = 2] = "EMITTER";
    Scope[Scope["RECIPIENT"] = 3] = "RECIPIENT";
    Scope[Scope["CHANNEL"] = 4] = "CHANNEL";
    Scope[Scope["SERVER"] = 5] = "SERVER";
})(Scope || (exports.Scope = Scope = {}));
/**
 * Property IDs
 */
var PropertyId;
(function (PropertyId) {
    PropertyId[PropertyId["CONTENT"] = 16] = "CONTENT";
    PropertyId[PropertyId["CHANNEL"] = 17] = "CHANNEL";
    PropertyId[PropertyId["DIRECTION"] = 18] = "DIRECTION";
    PropertyId[PropertyId["TYPE"] = 19] = "TYPE";
    PropertyId[PropertyId["LANG_SOURCE"] = 20] = "LANG_SOURCE";
    PropertyId[PropertyId["LANG_TARGET"] = 21] = "LANG_TARGET";
    PropertyId[PropertyId["COLOR_MODE"] = 22] = "COLOR_MODE";
    PropertyId[PropertyId["FORMAT_PAPI"] = 23] = "FORMAT_PAPI";
    PropertyId[PropertyId["SLEEP_MILLIS"] = 24] = "SLEEP_MILLIS";
    PropertyId[PropertyId["SOUNDS"] = 25] = "SOUNDS";
    PropertyId[PropertyId["CANCELLED"] = 26] = "CANCELLED";
    PropertyId[PropertyId["PROCESSED"] = 27] = "PROCESSED";
    PropertyId[PropertyId["TRANSLATE"] = 28] = "TRANSLATE";
})(PropertyId || (exports.PropertyId = PropertyId = {}));
/**
 * Routing decision codes
 */
var Decision;
(function (Decision) {
    Decision[Decision["LOG"] = 0] = "LOG";
    Decision[Decision["DROP"] = 1] = "DROP";
    Decision[Decision["REJECT"] = 2] = "REJECT";
    Decision[Decision["REDIRECT"] = 3] = "REDIRECT";
    Decision[Decision["CHANNEL_REDIRECT"] = 4] = "CHANNEL_REDIRECT";
    Decision[Decision["RATE_LIMIT"] = 5] = "RATE_LIMIT";
    Decision[Decision["CONTINUE"] = 6] = "CONTINUE";
})(Decision || (exports.Decision = Decision = {}));
/**
 * Register names for parser
 */
exports.REGISTER_NAMES = {
    'R0': 0, 'R1': 1, 'R2': 2, 'R3': 3,
    'R4': 4, 'R5': 5, 'R6': 6, 'R7': 7,
    'IP': 8, 'SP': 9, 'FP': 10, 'FL': 11,
};
//# sourceMappingURL=types.js.map