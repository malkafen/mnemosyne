package com.mnemosyne.app.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExtraDisk {

  @NotBlank(message = "Extra disk name is required")
  @Pattern(
      regexp = "^[a-z0-9][a-z0-9-]{0,19}$",
      message =
          "Extra disk name must be 1-20 characters of a-z, 0-9 and '-', starting with a letter or"
              + " digit (it becomes /dev/disk/by-id/virtio-<name> in the guest)")
  private String name;

  @Min(value = 1, message = "Extra disk size must be at least 1 GiB")
  @Max(value = 65536, message = "Extra disk size must not exceed 65536 GiB (64 TiB)")
  private int size;

  @NotBlank(message = "Extra disk pool name is required")
  private String pool;

  public String volName(String vmName) {
    return vmName + "-" + name + ".qcow2";
  }
}
