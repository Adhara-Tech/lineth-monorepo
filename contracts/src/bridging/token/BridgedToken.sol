// SPDX-License-Identifier: AGPL-3.0
pragma solidity ^0.8.33;

import { ERC20PermitUpgradeable } from "@openzeppelin/contracts-upgradeable/token/ERC20/extensions/ERC20PermitUpgradeable.sol";

/**
 * @title BridgedToken Contract
 * @notice ERC-20 token created when a native token is bridged to a target chain.
 * @custom:security-contact security-report@linea.build
 */
contract BridgedToken is ERC20PermitUpgradeable {
  event CreateHoldExecuted(address indexed owner, address indexed spender, uint256 value);

  mapping(address => mapping(address => uint256)) private _holds;

  address public bridge;
  uint8 public _decimals;
  /**
   * @notice Initializes the BridgedToken contract.
   * @dev Disables OpenZeppelin's initializer mechanism for safety.
   */

  /// @dev Keep free storage slots for future implementation updates to avoid storage collision.
  uint256[50] private __gap;

  error OnlyBridge(address bridgeAddress);

  /// @dev Disable constructor for safety
  /// @custom:oz-upgrades-unsafe-allow constructor
  constructor() {
    _disableInitializers();
  }

  function initialize(string memory _tokenName, string memory _tokenSymbol, uint8 _tokenDecimals) external initializer {
    __ERC20_init(_tokenName, _tokenSymbol);
    __ERC20Permit_init(_tokenName);
    bridge = msg.sender;
    _decimals = _tokenDecimals;
  }

  /// @dev Ensures call come from the bridge.
  modifier onlyBridge() {
    if (msg.sender != bridge) revert OnlyBridge(bridge);
    _;
  }

  /**
   * @dev Called by the bridge to mint tokens during a bridge transaction.
   * @param _recipient The address to receive the minted tokens.
   * @param _amount The amount of tokens to mint.
   */
  function mint(address _recipient, uint256 _amount) external onlyBridge {
    _mint(_recipient, _amount);
  }

  /**
   * @dev Called by the bridge to burn tokens during a bridge transaction.
   * @dev User should first have allowed the bridge to spend tokens on their behalf.
   * @param _account The account from which tokens will be burned.
   * @param _amount The amount of tokens to burn.
   */
  function burn(address _account, uint256 _amount) external onlyBridge {
    _spendAllowance(_account, msg.sender, _amount);
    _burn(_account, _amount);
  }

  /**
   * @dev Overrides ERC-20 default function to support tokens with different decimals.
   * @return The number of decimal.
   */
  function decimals() public view override returns (uint8) {
    return _decimals;
  }

  function getHold(address owner, address spender) public view virtual returns (uint256) {
    return _holds[owner][spender];
  }

  function createHold(address owner, address spender, uint256 amount) public {
    require(owner != address(0), "ERC20: approve from the zero address");
    require(spender != address(0), "ERC20: approve to the zero address");

    _holds[owner][spender] = amount;
    emit CreateHoldExecuted(owner, spender, amount);
  }
}
